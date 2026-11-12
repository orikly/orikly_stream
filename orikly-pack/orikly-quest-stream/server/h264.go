package main

// H.264 headsets (app 0.5.0+): frames depend on each other, so every viewer must get them all, in order,
// starting with a key frame. The server keeps the frames since the last key frame (a new viewer starts
// at once) and asks the headset for a fresh key frame when someone starts watching or falls behind.
//
// Binary message from the headset (passed to the page as is): 1 byte type (1 = key frame with SPS/PPS,
// 2 = ordinary frame) + H.264 Annex B data.

import (
	"context"
	"net/http"
	"strings"
	"time"
)

const (
	frameKey   = 1
	gopMaxLen  = 600     // frames kept since the last key frame
	gopMaxSize = 8 << 20 // bytes
	viewerBuf  = 120     // frames queued per viewer before it is considered behind
)

type subscriber struct {
	ch      chan []byte
	needKey bool // fell behind: skip frames until the next key frame
}

// onH264Frame: d.mu held. Keeps the GOP and hands the frame to every viewer in order.
func (d *device) onH264Frame(msg []byte) {
	key := len(msg) > 0 && msg[0] == frameKey
	if key {
		d.gop = append(d.gop[:0], msg)
		d.gopSize = len(msg)
	} else if len(d.gop) > 0 {
		if len(d.gop) >= gopMaxLen || d.gopSize+len(msg) > gopMaxSize {
			d.gop, d.gopSize = nil, 0 // too long without a key frame
			go d.requestKey()
		} else {
			d.gop = append(d.gop, msg)
			d.gopSize += len(msg)
		}
	}
	for s := range d.subs {
		if s.needKey {
			if !key {
				continue
			}
			s.needKey = false
		}
		select {
		case s.ch <- msg:
		default: // the viewer is behind: drop until the next key frame, ask the headset for one
			s.needKey = true
			go d.requestKey()
		}
	}
}

// requestKey asks the headset for a key frame (at most once a second).
func (d *device) requestKey() {
	d.mu.Lock()
	if time.Since(d.lastKeyAsk) < time.Second {
		d.mu.Unlock()
		return
	}
	d.lastKeyAsk = time.Now()
	d.mu.Unlock()
	d.sendWantKey(true)
}

// watchH264 streams all frames of an H.264 headset to one viewer, in order.
func watchH264(ctx context.Context, d *device, hq bool, send func([]byte) error) {
	s := &subscriber{ch: make(chan []byte, viewerBuf+gopMaxLen)}
	d.mu.Lock()
	if d.viewers == 0 {
		d.onWatchStart()
	}
	d.viewers++
	if hq {
		d.hqViewers++
	}
	if d.subs == nil {
		d.subs = map[*subscriber]struct{}{}
	}
	d.subs[s] = struct{}{}
	if len(d.gop) > 0 {
		for _, f := range d.gop { // start at once from the last key frame
			s.ch <- f
		}
	} else {
		s.needKey = true
	}
	d.mu.Unlock()
	d.sendWantKey(true)
	defer func() {
		d.mu.Lock()
		delete(d.subs, s)
		d.viewers--
		if hq {
			d.hqViewers--
		}
		d.mu.Unlock()
		d.sendWant()
	}()
	for {
		d.mu.Lock()
		connected := d.conn != nil
		d.mu.Unlock()
		if !connected {
			return
		}
		select {
		case <-ctx.Done():
			return
		case f := <-s.ch:
			if send(f) != nil {
				return
			}
		case <-time.After(time.Second): // check that the headset is still connected
		}
	}
}

// wantAll re-sends "want" to every headset (after FPS changed).
func wantAll() {
	devices.mu.Lock()
	list := make([]*device, 0, len(devices.devices))
	for _, d := range devices.devices {
		list = append(list, d)
	}
	devices.mu.Unlock()
	for _, d := range list {
		d.sendWant()
	}
}

// sameOrigin: requests from our own page only (other web pages open in the laptop's browser must not
// drive the server). Through "tailscale serve" the page comes from https://<machine>.<tailnet>.ts.net.
func sameOrigin(r *http.Request) bool {
	o := r.Header.Get("Origin")
	if o == "" || strings.HasSuffix(o, "://"+r.Host) {
		return true
	}
	if i := strings.Index(o, "://"); i >= 0 {
		host := o[i+3:]
		if j := strings.IndexByte(host, ':'); j >= 0 {
			host = host[:j]
		}
		return strings.HasSuffix(host, ".ts.net")
	}
	return false
}
