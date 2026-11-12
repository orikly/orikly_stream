package main

// Lesson app: the teacher types it on the page (one for the whole class). Every headset reports the app
// it is in; a headset outside the lesson app gets a red frame on the page and a line in the log.
// The Quest menu (Meta button) does not count: the headset keeps reporting the lesson app as "paused".

import (
	"encoding/json"
	"log"
	"net/http"
	"strings"
)

func currentLesson() string {
	fpsMu.Lock()
	defer fpsMu.Unlock()
	return cfg.Lesson
}

// handleLesson: GET returns the lesson app, POST ?app=... sets it (empty = no check) and saves it.
func handleLesson(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodPost {
		if !sameOrigin(r) {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		app := strings.TrimSpace(r.URL.Query().Get("app"))
		fpsMu.Lock()
		changed := cfg.Lesson != app
		cfg.Lesson = app
		if changed {
			saveConfig()
		}
		fpsMu.Unlock()
		if changed {
			if app == "" {
				log.Printf("lesson app: none (no check)")
			} else {
				log.Printf("lesson app: %s", app)
			}
		}
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	json.NewEncoder(w).Encode(map[string]string{"app": currentLesson()})
}

type appStatus struct {
	Capturing   *bool  `json:"capturing"`
	UsageAccess *bool  `json:"usageAccess"`
	App         string `json:"app"`
	AppPkg      string `json:"appPkg"`
	AppState    string `json:"appState"`
}

// lessonCheck says whether the headset is in the lesson app.
// state: "" (no lesson set / headset offline), "noinfo" (headset sends no app info), "ok", "left".
func (d *device) lessonCheck(lesson string) (state, text string) { // d.mu held
	if lesson == "" || d.conn == nil {
		return "", ""
	}
	var s appStatus
	json.Unmarshal(d.status, &s)
	if s.UsageAccess == nil {
		return "noinfo", "no app info (update the app on the headset)"
	}
	if !*s.UsageAccess {
		return "noinfo", "no app info (Usage access not allowed on the headset)"
	}
	name := s.App
	if name == "" {
		name = s.AppPkg
	}
	same := strings.EqualFold(s.App, lesson) || strings.EqualFold(s.AppPkg, lesson)
	switch {
	case name == "":
		return "left", "no app open"
	case !same:
		return "left", "in " + name
	case s.AppState == "closed":
		return "left", name + " closed"
	}
	return "ok", ""
}
