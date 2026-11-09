package main

// Classes: the same headsets are used by different classes, so every class has its own names
// for the headsets (student names). The teacher picks the class at the top of the page; names are
// given by clicking a tile name. Everything is kept in orikly-server.json, keyed by the headset id.

import (
	"encoding/json"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
)

type classCfg struct {
	Name  string            `json:"name"`
	Names map[string]string `json:"names"` // headset id -> name in this class
}

// ensureClasses: three classes by default. fpsMu held.
func ensureClasses() {
	for len(cfg.Classes) < 3 {
		cfg.Classes = append(cfg.Classes, classCfg{Name: "Class " + strconv.Itoa(len(cfg.Classes)+1)})
	}
	for i := range cfg.Classes {
		if cfg.Classes[i].Names == nil {
			cfg.Classes[i].Names = map[string]string{}
		}
	}
	if cfg.Class < 0 || cfg.Class >= len(cfg.Classes) {
		cfg.Class = 0
	}
}

// saveConfig writes orikly-server.json. fpsMu held.
func saveConfig() {
	b, _ := json.MarshalIndent(cfg, "", "  ")
	if err := os.WriteFile(configPath, b, 0o600); err != nil {
		log.Printf("cannot save %s: %v", configPath, err)
	}
}

// studentName: the headset's name in the current class, or "".
func studentName(id string) string {
	fpsMu.Lock()
	defer fpsMu.Unlock()
	ensureClasses()
	return cfg.Classes[cfg.Class].Names[id]
}

// handleClasses:
//
//	GET                         -> {"current": 0, "classes": [{"name": "...", "names": {...}}]}
//	POST ?current=N             -> choose the class
//	POST ?rename=N&name=X       -> rename class N
//	POST ?class=N&id=ID&name=X  -> name of a headset in class N (empty name = remove)
func handleClasses(w http.ResponseWriter, r *http.Request) {
	fpsMu.Lock()
	ensureClasses()
	if r.Method == http.MethodPost {
		if o := r.Header.Get("Origin"); o != "" && !strings.HasSuffix(o, "://"+r.Host) {
			fpsMu.Unlock()
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		q := r.URL.Query()
		idx := func(key string) (int, bool) {
			n, err := strconv.Atoi(q.Get(key))
			return n, err == nil && n >= 0 && n < len(cfg.Classes)
		}
		name := strings.TrimSpace(q.Get("name"))
		switch {
		case q.Has("current"):
			if n, ok := idx("current"); ok && n != cfg.Class {
				cfg.Class = n
				log.Printf("class: %s", cfg.Classes[n].Name)
				saveConfig()
			}
		case q.Has("rename"):
			if n, ok := idx("rename"); ok && name != "" {
				cfg.Classes[n].Name = name
				saveConfig()
			}
		case q.Has("class") && q.Get("id") != "":
			if n, ok := idx("class"); ok {
				if name == "" {
					delete(cfg.Classes[n].Names, q.Get("id"))
				} else {
					cfg.Classes[n].Names[q.Get("id")] = name
				}
				saveConfig()
			}
		default:
			fpsMu.Unlock()
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
	}
	b, _ := json.Marshal(map[string]any{"current": cfg.Class, "classes": cfg.Classes})
	fpsMu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.Write(b)
}
