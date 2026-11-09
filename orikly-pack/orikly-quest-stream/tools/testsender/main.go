// orikly-testsender: a fake headset for testing the path to the server.
// It draws a 16:9 picture (854x480; 1706x960 when the tile is expanded), compresses it to JPEG
// like the headset does and sends it to wss://stream.orikly.org/push with the same protocol
// (hello / status / frames, acknowledgements, at most a few frames in flight).
// The page shows it as one more tile, "Test sender 16:9". Every 2 s it prints what got through.
//
// The key: -key, or orikly-server.json next to this program (put it next to orikly-server).
package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"math/rand"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

var (
	addr    = flag.String("url", "wss://stream.orikly.org/push", "server address (ws://127.0.0.1:8090/push = without the tunnel)")
	keyFlag = flag.String("key", "", "access key (default: from orikly-server.json next to this program)")
	quality = flag.Int("q", 60, "JPEG quality")
	// Load test: several fake headsets with a fixed total traffic, sending without waiting for Start.
	count   = flag.Int("count", 1, "load test: number of fake headsets")
	mbit    = flag.Float64("mbit", 0, "load test: total traffic in Mbit/s (e.g. 14); 0 = normal mode (like one headset, waits for Start)")
	loadFps = flag.Int("fps", 24, "load test: frames per second per fake headset")
)

// Load test: prepared frames (a real JPEG padded to the needed size) and shared statistics.
var (
	loadFrames [][]byte
	agg        struct {
		sync.Mutex
		bytes, frames, waited, conns int
		rtts                         []time.Duration
	}
)

func loadMode() bool { return *mbit > 0 }

func prepareLoadFrames() {
	size := int(*mbit * 1e6 / 8 / float64(*count) / float64(*loadFps))
	for i := 0; i < 24; i++ {
		jp := picture(854, 480, i*5)
		for q := *quality - 5; len(jp) > size && q >= 5; q -= 5 { // too heavy for the asked traffic: lower JPEG quality
			jp = pictureQ(854, 480, i*5, q)
		}
		if len(jp) > size {
			jp = jp[:size] // still too big: a cut picture is fine for a traffic test
		}
		if len(jp) < size { // bytes after the end of a JPEG are ignored by decoders
			jp = append(jp, make([]byte, size-len(jp))...)
		}
		loadFrames = append(loadFrames, jp)
	}
	fmt.Printf("Load test: %d fake headsets x %d fps x %d KB = %.1f Mbit/s in total\n",
		*count, *loadFps, len(loadFrames[0])/1024, float64(len(loadFrames[0])*8**loadFps**count)/1e6)
}

func loadReport() {
	start := time.Now()
	last := start
	for range time.Tick(2 * time.Second) {
		now := time.Now()
		agg.Lock()
		var sum, max time.Duration
		for _, r := range agg.rtts {
			sum += r
			if r > max {
				max = r
			}
		}
		avg := time.Duration(0)
		if len(agg.rtts) > 0 {
			avg = sum / time.Duration(len(agg.rtts))
		}
		sec := now.Sub(last).Seconds()
		fmt.Printf("%s  connected %d/%d, sent %.2f Mbit/s, %.1f fps per headset, round trip avg %d ms max %d ms, waited for acks %d times\n",
			now.Format("15:04:05"), agg.conns, *count, float64(agg.bytes)*8/sec/1e6,
			float64(agg.frames)/sec/float64(max1(agg.conns)), avg.Milliseconds(), max.Milliseconds(), agg.waited)
		agg.bytes, agg.frames, agg.waited, agg.rtts = 0, 0, 0, agg.rtts[:0]
		agg.Unlock()
		last = now
	}
}

func max1(n int) int {
	if n < 1 {
		return 1
	}
	return n
}

func loadKey() string {
	if *keyFlag != "" {
		return *keyFlag
	}
	exe, _ := os.Executable()
	if b, err := os.ReadFile(filepath.Join(filepath.Dir(exe), "orikly-server.json")); err == nil {
		var c struct{ Key string }
		if json.Unmarshal(b, &c) == nil && c.Key != "" {
			return c.Key
		}
	}
	fmt.Print("Enter the access key (the same as STREAM_KEY): ")
	line, _ := bufio.NewReader(os.Stdin).ReadString('\n')
	return strings.TrimSpace(line)
}

// 5x7 digits for the frame counter and clock.
var font = map[rune][7]string{
	'0': {"01110", "10001", "10011", "10101", "11001", "10001", "01110"},
	'1': {"00100", "01100", "00100", "00100", "00100", "00100", "01110"},
	'2': {"01110", "10001", "00001", "00010", "00100", "01000", "11111"},
	'3': {"11110", "00001", "00001", "01110", "00001", "00001", "11110"},
	'4': {"00010", "00110", "01010", "10010", "11111", "00010", "00010"},
	'5': {"11111", "10000", "11110", "00001", "00001", "10001", "01110"},
	'6': {"00110", "01000", "10000", "11110", "10001", "10001", "01110"},
	'7': {"11111", "00001", "00010", "00100", "01000", "01000", "01000"},
	'8': {"01110", "10001", "10001", "01110", "10001", "10001", "01110"},
	'9': {"01110", "10001", "10001", "01111", "00001", "00010", "01100"},
	':': {"00000", "00100", "00100", "00000", "00100", "00100", "00000"},
	'.': {"00000", "00000", "00000", "00000", "00000", "01100", "01100"},
	' ': {"00000", "00000", "00000", "00000", "00000", "00000", "00000"},
}

func text(img *image.RGBA, s string, x0, y0, scale int, c color.RGBA) {
	for i, r := range s {
		g, ok := font[r]
		if !ok {
			continue
		}
		for y := 0; y < 7; y++ {
			for x := 0; x < 5; x++ {
				if g[y][x] == '1' {
					for dy := 0; dy < scale; dy++ {
						for dx := 0; dx < scale; dx++ {
							img.SetRGBA(x0+(i*6+x)*scale+dx, y0+y*scale+dy, c)
						}
					}
				}
			}
		}
	}
}

// picture: moving colour bands + light noise (JPEG ~28 KB at 854x480, close to a real headset frame) + clock.
func picture(w, h, n int) []byte { return pictureQ(w, h, n, *quality) }

func pictureQ(w, h, n, q int) []byte {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	rnd := rand.New(rand.NewSource(int64(n)))
	pix := img.Pix
	for y := 0; y < h; y++ {
		gy := uint8(y * 255 / h)
		for x := 0; x < w; x++ {
			v := uint8(rnd.Intn(12))
			i := y*img.Stride + x*4
			pix[i], pix[i+1], pix[i+2], pix[i+3] = uint8((x+n*6)*255/w)+v, gy+v/2, 140+v, 255
		}
	}
	bar := (n * 12) % w
	for y := 0; y < h; y++ {
		for x := bar; x < bar+w/30 && x < w; x++ {
			img.SetRGBA(x, y, color.RGBA{255, 255, 255, 255})
		}
	}
	scale := h / 60
	text(img, fmt.Sprintf("%06d", n), w/20, h/10, scale, color.RGBA{0, 0, 0, 255})
	text(img, time.Now().Format("15:04:05.0"), w/20, h/10+scale*10, scale, color.RGBA{0, 0, 0, 255})
	var b bytes.Buffer
	jpeg.Encode(&b, img, &jpeg.Options{Quality: q})
	return b.Bytes()
}

func main() {
	flag.Parse()
	key := loadKey()
	host, _ := os.Hostname()
	u := *addr + "?key=" + url.QueryEscape(key)
	fmt.Println("Sending a 16:9 test picture to", *addr)
	if loadMode() {
		prepareLoadFrames()
		go loadReport()
	}
	for i := 1; i <= *count; i++ {
		go func(i int) {
			for {
				if err := run(u, host, i); err != nil {
					fmt.Printf("fake headset %d: %v — retrying in 3 s\n", i, err)
				}
				time.Sleep(3 * time.Second)
			}
		}(i)
		time.Sleep(100 * time.Millisecond)
	}
	select {}
}

func run(u, host string, idx int) error {
	c, resp, err := websocket.DefaultDialer.Dial(u, nil)
	if err != nil {
		if resp != nil {
			return fmt.Errorf("%v (HTTP %d: 403 = wrong key, 502 = server not running, 530 = tunnel down)", err, resp.StatusCode)
		}
		return err
	}
	defer c.Close()
	var wmu sync.Mutex
	send := func(t int, b []byte) error {
		wmu.Lock()
		defer wmu.Unlock()
		c.SetWriteDeadline(time.Now().Add(10 * time.Second))
		return c.WriteMessage(t, b)
	}
	id, name := "testsender-"+host, "Test sender 16:9"
	if *count > 1 {
		id, name = fmt.Sprintf("%s-%d", id, idx), fmt.Sprintf("%s #%d", name, idx)
	}
	hello, _ := json.Marshal(map[string]any{"type": "hello", "id": id, "name": name,
		"model": "test", "version": "test"})
	if err := send(websocket.TextMessage, hello); err != nil {
		return err
	}
	if loadMode() {
		agg.Lock()
		agg.conns++
		agg.Unlock()
		defer func() { agg.Lock(); agg.conns--; agg.Unlock() }()
	} else {
		fmt.Println("connected. Open the page and press Start on the tile \"Test sender 16:9\".")
	}

	var mu sync.Mutex
	on, hq, fps := loadMode(), false, 15
	cropW := 1.0
	if loadMode() {
		fps = *loadFps
	}
	var sentTimes []time.Time
	type sample struct {
		at  time.Time
		rtt time.Duration
	}
	var samples []sample
	var winRtt []time.Duration
	errc := make(chan error, 1)
	go func() {
		for {
			_, m, err := c.ReadMessage()
			if err != nil {
				errc <- err
				return
			}
			var w struct {
				Type   string
				On, Hq bool
				Fps    int
				Crop   []float64
			}
			json.Unmarshal(m, &w)
			mu.Lock()
			switch w.Type {
			case "want":
				if !loadMode() { // load test sends all the time at its own rate
					on, hq = w.On, w.Hq
					if w.Fps > 0 {
						fps = w.Fps
					}
					cropW = 1
					if len(w.Crop) == 2 && w.Crop[1] > w.Crop[0] {
						cropW = w.Crop[1] - w.Crop[0] // like the headset: a narrower picture
					}
				}
			case "ack":
				if len(sentTimes) > 0 {
					r := time.Since(sentTimes[0])
					sentTimes = sentTimes[1:]
					samples = append(samples, sample{time.Now(), r})
					winRtt = append(winRtt, r)
					if loadMode() {
						agg.Lock()
						agg.rtts = append(agg.rtts, r)
						agg.Unlock()
					}
				}
			}
			mu.Unlock()
		}
	}()
	// Same rule as the headset: up to 3 frames in flight always, up to 8 while there is no queue.
	canSend := func(now time.Time) bool {
		n := len(sentTimes)
		if n < 3 {
			return true
		}
		if n >= 8 {
			return false
		}
		for len(samples) > 0 && now.Sub(samples[0].at) > 30*time.Second {
			samples = samples[1:]
		}
		if len(samples) == 0 {
			return false
		}
		min := samples[0].rtt
		for _, s := range samples {
			if s.rtt < min {
				min = s.rtt
			}
		}
		return now.Sub(sentTimes[0]) < min+60*time.Millisecond
	}
	n, sent, bytesSent, waited := 0, 0, 0, 0
	lastReport, lastStatus := time.Now(), time.Time{}
	next := time.Now()
	for {
		select {
		case err := <-errc:
			return err
		default:
		}
		mu.Lock()
		o, h, f := on, hq, fps
		mu.Unlock()
		now := time.Now()
		if now.Sub(lastStatus) >= 2*time.Second {
			mu.Lock()
			var avg time.Duration
			if len(winRtt) > 0 {
				avg = winRtt[len(winRtt)-1]
			}
			mu.Unlock()
			st, _ := json.Marshal(map[string]any{"type": "status", "capturing": true, "fps": f, "targetFps": f,
				"sentFps": float64(sent) / now.Sub(lastReport).Seconds(), "level": 0, "rtt": avg.Milliseconds(),
				"output": "854x480", "source": "854x480", "capture": "854x480", "sysFps": f})
			if err := send(websocket.TextMessage, st); err != nil {
				return err
			}
			lastStatus = now
		}
		if now.Sub(lastReport) >= 2*time.Second && !loadMode() {
			mu.Lock()
			var sum, max time.Duration
			for _, r := range winRtt {
				sum += r
				if r > max {
					max = r
				}
			}
			cnt := len(winRtt)
			winRtt = winRtt[:0]
			inflight := len(sentTimes)
			mu.Unlock()
			sec := now.Sub(lastReport).Seconds()
			if o {
				avg := time.Duration(0)
				if cnt > 0 {
					avg = sum / time.Duration(cnt)
				}
				kb := 0
				if sent > 0 {
					kb = bytesSent / sent / 1024
				}
				fmt.Printf("%s  sent %.1f of %d fps, %d KB/frame, %.2f Mbit/s, round trip avg %d ms max %d ms, in flight %d, waited for acks %d times\n",
					now.Format("15:04:05"), float64(sent)/sec, f, kb, float64(bytesSent)*8/sec/1e6,
					avg.Milliseconds(), max.Milliseconds(), inflight, waited)
			} else {
				fmt.Printf("%s  idle: nobody is watching (press Start on the tile)\n", now.Format("15:04:05"))
			}
			sent, bytesSent, waited, lastReport = 0, 0, 0, now
		}
		if !o {
			time.Sleep(100 * time.Millisecond)
			continue
		}
		if now.Before(next) {
			time.Sleep(time.Until(next))
			continue
		}
		next = next.Add(time.Second / time.Duration(f))
		if now.Sub(next) > time.Second {
			next = now
		}
		w, hh := 854, 480
		if h {
			w, hh = 1706, 960
		}
		mu.Lock()
		w = int(float64(w)*cropW) &^ 1
		mu.Unlock()
		mu.Lock()
		ok := canSend(time.Now())
		mu.Unlock()
		if !ok {
			waited++ // window full: skip this frame, send the freshest one later (like the headset)
			if loadMode() {
				agg.Lock()
				agg.waited++
				agg.Unlock()
			}
			continue
		}
		var jp []byte
		if loadMode() {
			jp = loadFrames[n%len(loadFrames)]
		} else {
			jp = picture(w, hh, n)
		}
		n++
		mu.Lock()
		sentTimes = append(sentTimes, time.Now())
		mu.Unlock()
		if err := send(websocket.BinaryMessage, jp); err != nil {
			return err
		}
		sent++
		bytesSent += len(jp)
		if loadMode() {
			agg.Lock()
			agg.bytes += len(jp)
			agg.frames++
			agg.Unlock()
		}
	}
}
