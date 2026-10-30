// orikly-server: runs on the observer laptop (Windows).
//
// Headsets connect over the internet (through Cloudflare Tunnel) to /push with a WebSocket,
// and send JPEG frames only while someone is watching. The laptop's Chrome opens
// http://localhost:8090 and gets the same grid page as in local mode.
//
// Only /push is meant to be reached through the tunnel; everything else answers to this
// laptop only (requests coming through Cloudflare carry a Cf-Connecting-Ip header).
package main

import (
	"bufio"
	"context"
	_ "embed"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

//go:embed index.html
var indexHTML []byte

type config struct {
	Key  string `json:"key"`
	Port int    `json:"port"`
}

type device struct {
	id   string // unique per headset; names can repeat (often just "Quest 3")
	name string

	mu        sync.Mutex
	conn      *websocket.Conn
	writeMu   sync.Mutex
	frame     []byte
	seq       uint64
	newFrame  chan struct{} // closed and replaced on every frame
	viewers   int
	hqViewers int
	model     string
	version   string
	status    json.RawMessage
	lastSeen  time.Time
}

type hub struct {
	mu      sync.Mutex
	devices map[string]*device
}

var (
	cfg      config
	devices  = hub{devices: map[string]*device{}}
	upgrader = websocket.Upgrader{
		ReadBufferSize:  64 * 1024,
		WriteBufferSize: 4 * 1024,
		CheckOrigin:     func(*http.Request) bool { return true }, // headsets send no Origin
	}
)

func (h *hub) get(id string, create bool) *device {
	h.mu.Lock()
	defer h.mu.Unlock()
	d := h.devices[id]
	if d == nil && create {
		d = &device{id: id, newFrame: make(chan struct{})}
		h.devices[id] = d
	}
	return d
}

// sendWant tells the headset whether to send frames and in which quality.
func (d *device) sendWant() {
	d.mu.Lock()
	conn, on, hq := d.conn, d.viewers > 0, d.hqViewers > 0
	d.mu.Unlock()
	if conn == nil {
		return
	}
	d.writeMu.Lock()
	defer d.writeMu.Unlock()
	conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	// acks: this server confirms every frame, so the headset keeps only a few frames in flight.
	conn.WriteJSON(map[string]any{"type": "want", "on": on, "hq": hq, "acks": true})
}

// ack confirms a received frame. Without this, frames piled up in Cloudflare and network
// buffers whenever the laptop's internet was slower than the headsets, and delay grew to 5-7 s.
func (d *device) ack(conn *websocket.Conn) {
	d.writeMu.Lock()
	defer d.writeMu.Unlock()
	conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	conn.WriteMessage(websocket.TextMessage, []byte(`{"type":"ack"}`))
}

// ---------- headsets ----------

func handlePush(w http.ResponseWriter, r *http.Request) {
	key := r.URL.Query().Get("key")
	if key == "" {
		key = r.Header.Get("X-Stream-Key")
	}
	if key != cfg.Key {
		if key != "" {
			log.Printf("rejected a headset with a wrong key from %s (check STREAM_KEY and orikly-server.json)", clientIP(r))
		}
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	conn.SetReadLimit(8 << 20)

	// First message must be hello with the headset name.
	conn.SetReadDeadline(time.Now().Add(15 * time.Second))
	var hello struct {
		Type, ID, Name, Model, Version string
	}
	if _, msg, err := conn.ReadMessage(); err != nil || json.Unmarshal(msg, &hello) != nil ||
		hello.Type != "hello" || strings.TrimSpace(hello.Name) == "" {
		conn.Close()
		return
	}
	name := strings.TrimSpace(hello.Name)
	id := strings.TrimSpace(hello.ID)
	if id == "" {
		id = name // app before v0.3.1 sends no id
	}
	d := devices.get(id, true)

	d.mu.Lock()
	old := d.conn
	d.conn = conn
	d.name, d.model, d.version = name, hello.Model, hello.Version
	d.lastSeen = time.Now()
	d.mu.Unlock()
	if old != nil {
		old.Close() // same headset reconnected
	}
	log.Printf("connected: %s [%s] (%s, app %s) from %s", name, shortID(id), hello.Model, hello.Version, clientIP(r))
	d.sendWant()

	// Ping/pong is answered by the headset's WebSocket library; we only need data or pings to arrive.
	conn.SetPingHandler(func(data string) error {
		conn.SetReadDeadline(time.Now().Add(90 * time.Second))
		d.writeMu.Lock()
		defer d.writeMu.Unlock()
		return conn.WriteControl(websocket.PongMessage, []byte(data), time.Now().Add(5*time.Second))
	})
	for {
		conn.SetReadDeadline(time.Now().Add(90 * time.Second))
		typ, msg, err := conn.ReadMessage()
		if err != nil {
			break
		}
		d.mu.Lock()
		d.lastSeen = time.Now()
		if typ == websocket.BinaryMessage {
			d.frame = msg
			d.seq++
			close(d.newFrame)
			d.newFrame = make(chan struct{})
			d.mu.Unlock()
			d.ack(conn)
			continue
		} else {
			var m struct{ Type string }
			if json.Unmarshal(msg, &m) == nil && m.Type == "status" {
				d.status = append(json.RawMessage(nil), msg...)
			}
		}
		d.mu.Unlock()
	}

	d.mu.Lock()
	if d.conn == conn {
		d.conn = nil
		close(d.newFrame) // wake viewers so they notice
		d.newFrame = make(chan struct{})
		log.Printf("disconnected: %s [%s]", name, shortID(id))
	}
	d.mu.Unlock()
	conn.Close()
}

// ---------- laptop page ----------

func localOnly(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Cf-Connecting-Ip") != "" {
			http.NotFound(w, r)
			return
		}
		next(w, r)
	}
}

func handleIndex(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Write(indexHTML)
}

func handleDevices(w http.ResponseWriter, r *http.Request) {
	devices.mu.Lock()
	list := make([]*device, 0, len(devices.devices))
	for _, d := range devices.devices {
		list = append(list, d)
	}
	devices.mu.Unlock()
	type item struct {
		ID      string          `json:"id"`
		Name    string          `json:"name"`
		Label   string          `json:"label"` // name, plus the id tail when names repeat
		Online  bool            `json:"online"`
		Model   string          `json:"model"`
		Version string          `json:"version"`
		Viewers int             `json:"viewers"`
		Status  json.RawMessage `json:"status,omitempty"`
	}
	out := []item{}
	count := map[string]int{}
	for _, d := range list {
		d.mu.Lock()
		out = append(out, item{d.id, d.name, d.name, d.conn != nil, d.model, d.version, d.viewers, d.status})
		d.mu.Unlock()
		count[d.name]++
	}
	for i := range out {
		if count[out[i].Name] > 1 && out[i].ID != out[i].Name {
			out[i].Label = out[i].Name + " · " + shortID(out[i].ID)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Label < out[j].Label })
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	json.NewEncoder(w).Encode(out)
}

// openViewer finds an online headset, or answers 404.
func openViewer(w http.ResponseWriter, r *http.Request, prefix string) *device {
	d := devices.get(strings.TrimPrefix(r.URL.Path, prefix), false)
	if d == nil {
		http.Error(w, "unknown headset", http.StatusNotFound)
		return nil
	}
	d.mu.Lock()
	online := d.conn != nil
	d.mu.Unlock()
	if !online {
		http.Error(w, "headset offline", http.StatusNotFound)
		return nil
	}
	return d
}

// watch asks the headset for frames while this viewer is open and hands every new frame to send.
func watch(ctx context.Context, d *device, hq bool, send func([]byte) error) {
	d.mu.Lock()
	d.viewers++
	if hq {
		d.hqViewers++
	}
	lastSeq := d.seq // do not show an old picture: wait for a fresh one
	d.mu.Unlock()
	d.sendWant()
	defer func() {
		d.mu.Lock()
		d.viewers--
		if hq {
			d.hqViewers--
		}
		d.mu.Unlock()
		d.sendWant()
	}()

	sentAny := false
	for {
		d.mu.Lock()
		wait, connected := d.newFrame, d.conn != nil
		d.mu.Unlock()
		if !connected {
			return
		}
		select {
		case <-ctx.Done():
			return
		case <-wait:
		case <-time.After(time.Second): // static screen: repeat the last frame as keep-alive
		}
		d.mu.Lock()
		frame, seq := d.frame, d.seq
		d.mu.Unlock()
		// Repeat only a frame from this session, never one left from before.
		if frame == nil || (seq == lastSeq && !sentAny) {
			continue
		}
		lastSeq, sentAny = seq, true
		if send(frame) != nil {
			return
		}
	}
}

// handleView: one WebSocket per tile. Chrome allows only 6 HTTP streams to one address,
// so with 7+ headsets MJPEG over HTTP stalls; WebSockets do not have that limit.
func handleView(w http.ResponseWriter, r *http.Request) {
	if o := r.Header.Get("Origin"); o != "" && !strings.HasSuffix(o, "://"+r.Host) {
		http.Error(w, "forbidden", http.StatusForbidden) // only our own page
		return
	}
	d := openViewer(w, r, "/view/")
	if d == nil {
		return
	}
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer conn.Close()
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	go func() { // notice when the page closes the socket
		for {
			if _, _, err := conn.ReadMessage(); err != nil {
				cancel()
				return
			}
		}
	}()
	watch(ctx, d, r.URL.Query().Get("hq") == "1", func(frame []byte) error {
		conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
		return conn.WriteMessage(websocket.BinaryMessage, frame)
	})
}

// handleStream sends the same MJPEG format as the headset itself (handy to open one headset in a tab).
func handleStream(w http.ResponseWriter, r *http.Request) {
	d := openViewer(w, r, "/stream/")
	if d == nil {
		return
	}
	flusher, _ := w.(http.Flusher)
	const boundary = "oriklyframe"
	w.Header().Set("Content-Type", "multipart/x-mixed-replace; boundary="+boundary)
	w.Header().Set("Cache-Control", "no-cache, no-store")
	w.WriteHeader(http.StatusOK)
	fmt.Fprintf(w, "--%s\r\n", boundary)
	if flusher != nil {
		flusher.Flush()
	}
	watch(r.Context(), d, r.URL.Query().Get("hq") == "1", func(frame []byte) error {
		if _, err := fmt.Fprintf(w, "Content-Type: image/jpeg\r\nContent-Length: %d\r\n\r\n", len(frame)); err != nil {
			return err
		}
		if _, err := w.Write(frame); err != nil {
			return err
		}
		if _, err := fmt.Fprintf(w, "\r\n--%s\r\n", boundary); err != nil {
			return err
		}
		if flusher != nil {
			flusher.Flush()
		}
		return nil
	})
}

func shortID(id string) string {
	if len(id) > 4 {
		return id[len(id)-4:]
	}
	return id
}

func clientIP(r *http.Request) string {
	if ip := r.Header.Get("Cf-Connecting-Ip"); ip != "" {
		return ip
	}
	return r.RemoteAddr
}

// ---------- startup ----------

func loadConfig() {
	exe, _ := os.Executable()
	path := filepath.Join(filepath.Dir(exe), "orikly-server.json")
	if b, err := os.ReadFile(path); err == nil {
		json.Unmarshal(b, &cfg)
	}
	if cfg.Port == 0 {
		cfg.Port = 8090
	}
	if strings.TrimSpace(cfg.Key) == "" {
		fmt.Print("First start. Enter the access key (the same as STREAM_KEY in GitHub): ")
		line, _ := bufio.NewReader(os.Stdin).ReadString('\n')
		cfg.Key = strings.TrimSpace(line)
		if cfg.Key == "" {
			fail("No key entered.")
		}
		b, _ := json.MarshalIndent(cfg, "", "  ")
		if err := os.WriteFile(path, b, 0o600); err != nil {
			fail("Cannot save " + path + ": " + err.Error())
		}
		fmt.Println("Saved to", path)
	}
}

func fail(msg string) {
	fmt.Println("ERROR:", msg)
	fmt.Println("Press Enter to close.")
	bufio.NewReader(os.Stdin).ReadString('\n')
	os.Exit(1)
}

func openBrowser(url string) {
	if runtime.GOOS == "windows" {
		exec.Command("rundll32", "url.dll,FileProtocolHandler", url).Start()
	}
}

func main() {
	log.SetFlags(log.Ltime)
	loadConfig()

	mux := http.NewServeMux()
	mux.HandleFunc("/push", handlePush)
	mux.HandleFunc("/api/devices", localOnly(handleDevices))
	mux.HandleFunc("/stream/", localOnly(handleStream))
	mux.HandleFunc("/view/", localOnly(handleView))
	mux.HandleFunc("/", localOnly(handleIndex))

	addr := fmt.Sprintf("127.0.0.1:%d", cfg.Port)
	url := fmt.Sprintf("http://localhost:%d", cfg.Port)
	fmt.Println("Orikly server is running. Keep this window open during the lesson.")
	fmt.Println("Page for Chrome:", url)
	fmt.Println("Close this window to stop.")
	if len(os.Args) < 2 || os.Args[1] != "-no-browser" {
		go func() { time.Sleep(time.Second); openBrowser(url) }()
	}
	if err := http.ListenAndServe(addr, mux); err != nil {
		fail(err.Error() + " (is orikly-server already running?)")
	}
}
