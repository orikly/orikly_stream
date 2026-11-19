package main

// Голос учителя → выбранные очки (с 0.5.9).
//
// Страница открывает WebSocket /voice (только наша страница, через Cloudflare — 404):
//   текст   {"targets": ["<id>", ...]} — кому сейчас говорить (меняется, когда учитель жмёт «Connect Voice»);
//   бинарно — звук с микрофона: PCM 16 бит, моно, 16 кГц, little-endian, кусками по 40 мс.
// Очкам уходит бинарное сообщение: 1 байт типа (3 = голос) + тот же PCM. Очки старее 0.5.9 бинарных
// сообщений от сервера не ждут и просто их пропускают.
//
// У каждых очков своя очередь (~1 с): медленные очки не задерживают голос остальным, а если очередь
// переполнена — старые куски выбрасываются, чтобы голос не отставал.

import (
	"encoding/json"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const (
	voiceType  = 3
	voiceQueue = 25 // кусков по 40 мс ≈ 1 с
)

var voiceMu sync.Mutex // очереди голоса создаются лениво

func handleVoice(w http.ResponseWriter, r *http.Request) {
	if !sameOrigin(r) {
		http.Error(w, "forbidden", http.StatusForbidden) // only our own page
		return
	}
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer conn.Close()
	conn.SetReadLimit(64 * 1024)
	var targets []string
	defer func() {
		if len(targets) > 0 {
			log.Printf("voice: off")
		}
	}()
	for {
		mt, data, err := conn.ReadMessage()
		if err != nil {
			return
		}
		switch mt {
		case websocket.TextMessage:
			var m struct {
				Targets []string `json:"targets"`
			}
			if json.Unmarshal(data, &m) != nil {
				continue
			}
			targets = m.Targets
			if len(targets) == 0 {
				log.Printf("voice: off")
				continue
			}
			names := make([]string, 0, len(targets))
			for _, id := range targets {
				if d := devices.get(id, false); d != nil {
					names = append(names, d.label())
				} else {
					names = append(names, shortID(id))
				}
			}
			log.Printf("voice: on → %d headset(s): %s", len(targets), strings.Join(names, ", "))
		case websocket.BinaryMessage:
			if len(data) == 0 || len(targets) == 0 {
				continue
			}
			msg := make([]byte, len(data)+1)
			msg[0] = voiceType
			copy(msg[1:], data)
			for _, id := range targets {
				if d := devices.get(id, false); d != nil {
					d.queueVoice(msg)
				}
			}
		}
	}
}

// queueVoice кладёт кусок голоса в очередь очков; переполнена — выбрасывает самый старый.
func (d *device) queueVoice(msg []byte) {
	voiceMu.Lock()
	if d.voice == nil {
		d.voice = make(chan []byte, voiceQueue)
		go d.voiceWriter(d.voice)
	}
	ch := d.voice
	voiceMu.Unlock()
	for {
		select {
		case ch <- msg:
			return
		default:
			select { // очередь полна: выбросить старое, чтобы голос не отставал
			case <-ch:
			default:
			}
		}
	}
}

// voiceWriter отправляет голос в соединение очков (тот же замок записи, что у want/ack).
func (d *device) voiceWriter(ch chan []byte) {
	for msg := range ch {
		d.mu.Lock()
		conn := d.conn
		d.mu.Unlock()
		if conn == nil {
			continue // очки не подключены — кусок пропадает
		}
		d.writeMu.Lock()
		conn.SetWriteDeadline(time.Now().Add(2 * time.Second))
		conn.WriteMessage(websocket.BinaryMessage, msg)
		d.writeMu.Unlock()
	}
}
