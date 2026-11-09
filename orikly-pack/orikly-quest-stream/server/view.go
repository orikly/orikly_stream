package main

// View: which part of the picture (by width) headsets send. A narrower part = a lighter frame and less
// traffic; the centre of a VR picture is where the student looks. Chosen on the page, sent in "want".

import (
	"log"
	"net/http"
	"strings"
)

var viewCrops = map[string][2]float64{
	"full":  {0, 1},
	"c75":   {0.125, 0.875},
	"c50":   {0.25, 0.75},
	"left":  {0, 0.5},
	"right": {0.5, 1},
}

func currentCrop() [2]float64 {
	fpsMu.Lock()
	defer fpsMu.Unlock()
	if c, ok := viewCrops[cfg.View]; ok {
		return c
	}
	return viewCrops[defaultView]
}

// defaultView: only the left eye (Paul, 0.4.12) — half the width, half the traffic.
const defaultView = "left"

// handleView: GET returns {"view": "..."}, POST ?view=full|c75|c50|left|right changes it for all headsets.
func handleViewMode(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodPost {
		if o := r.Header.Get("Origin"); o != "" && !strings.HasSuffix(o, "://"+r.Host) {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		v := r.URL.Query().Get("view")
		if _, ok := viewCrops[v]; !ok {
			http.Error(w, "bad view", http.StatusBadRequest)
			return
		}
		fpsMu.Lock()
		changed := cfg.View != v
		cfg.View = v
		if changed {
			saveConfig()
		}
		fpsMu.Unlock()
		if changed {
			log.Printf("view: %s", v)
			wantAll()
		}
	}
	fpsMu.Lock()
	v := cfg.View
	fpsMu.Unlock()
	if v == "" {
		v = defaultView
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.Write([]byte(`{"view":"` + v + `"}`))
}

// wantAll re-sends "want" to every headset (after FPS or view changed).
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
