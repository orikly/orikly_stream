package main

// Freeze and traffic log: answers "where is the bottleneck".
// - every pause > 1 s in frames from a headset that someone is watching is logged with start, end and duration;
// - every 10 s while watching: total incoming Mbit/s and per headset Mbit/s, fps, frame size, round trip.
// Everything also goes to orikly-log-<date>.txt next to the program, to send after a lesson.

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

const freezeAfter = time.Second

type netCounters struct {
	lastFrameAt time.Time
	freezeStart time.Time // zero = no freeze now
	bytes       int64     // since last traffic line
	frames      int
	maxGap      time.Duration
	freezes     int
}

// headsetStatus: the fields of the headset's status message we print.
type headsetStatus struct {
	Rtt    float64 `json:"rtt"`
	Level  int     `json:"level"`
	SysFps float64 `json:"sysFps"`
	Sent   float64 `json:"sentFps"`
}

func (d *device) parsedStatus() headsetStatus { // d.mu held
	var s headsetStatus
	json.Unmarshal(d.status, &s)
	return s
}

func (d *device) label() string { return fmt.Sprintf("%s [%s]", d.name, shortID(d.id)) }

// onFrame is called with d.mu held for every frame received from the headset.
func (d *device) onFrame(size int) {
	now := time.Now()
	n := &d.net
	if !n.lastFrameAt.IsZero() {
		if gap := now.Sub(n.lastFrameAt); gap > n.maxGap && d.viewers > 0 {
			n.maxGap = gap
		}
	}
	if !n.freezeStart.IsZero() {
		log.Printf("FREEZE end:   %s — no frames for %.1f s", d.label(), now.Sub(n.freezeStart).Seconds())
		n.freezeStart = time.Time{}
	}
	n.lastFrameAt = now
	n.bytes += int64(size)
	n.frames++
}

// onWatchStart: a viewer opened, so pauses before this moment do not count.
func (d *device) onWatchStart() { // d.mu held
	d.net.lastFrameAt = time.Now()
	d.net.freezeStart = time.Time{}
}

func monitorNetwork() {
	tick := time.NewTicker(200 * time.Millisecond)
	lastReport := time.Now()
	for now := range tick.C {
		devices.mu.Lock()
		list := make([]*device, 0, len(devices.devices))
		for _, d := range devices.devices {
			list = append(list, d)
		}
		devices.mu.Unlock()
		sort.Slice(list, func(i, j int) bool { return list[i].id < list[j].id })

		report := now.Sub(lastReport) >= 10*time.Second
		var parts []string
		var total int64
		for _, d := range list {
			d.mu.Lock()
			n := &d.net
			watched := d.viewers > 0 && d.conn != nil
			if watched && n.freezeStart.IsZero() && now.Sub(n.lastFrameAt) > freezeAfter {
				n.freezeStart = n.lastFrameAt
				n.freezes++
				s := d.parsedStatus()
				hint := ""
				if s.SysFps < 2 {
					hint = " — headset screen gives almost no frames (static screen?)"
				}
				log.Printf("FREEZE start: %s — no frames for 1 s (last report from headset: round trip %.0f ms, quality -%d, system %.1f fps)%s",
					d.label(), s.Rtt, s.Level, s.SysFps, hint)
			}
			if report {
				sec := now.Sub(lastReport).Seconds()
				total += n.bytes
				if watched || n.bytes > 0 {
					s := d.parsedStatus()
					avg := 0.0
					if n.frames > 0 {
						avg = float64(n.bytes) / float64(n.frames) / 1024
					}
					parts = append(parts, fmt.Sprintf("%s %.2f Mbit/s %.1f fps %.0f KB/frame, round trip %.0f ms, quality -%d, system %.0f fps, longest pause %.1f s, freezes %d",
						d.label(), float64(n.bytes)*8/sec/1e6, float64(n.frames)/sec, avg, s.Rtt, s.Level, s.SysFps, n.maxGap.Seconds(), n.freezes))
				}
				n.bytes, n.frames, n.maxGap, n.freezes = 0, 0, 0, 0
			}
			d.mu.Unlock()
		}
		if report {
			if len(parts) > 0 {
				log.Printf("traffic: total %.2f Mbit/s\n    %s", float64(total)*8/now.Sub(lastReport).Seconds()/1e6, strings.Join(parts, "\n    "))
			}
			lastReport = now
		}
	}
}

// logFile opens orikly-log-<date>.txt next to the program (nil if it cannot).
func logFile() io.Writer {
	exe, _ := os.Executable()
	path := filepath.Join(filepath.Dir(exe), "orikly-log-"+time.Now().Format("2006-01-02")+".txt")
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return nil
	}
	fmt.Println("Log file:", path)
	return f
}
