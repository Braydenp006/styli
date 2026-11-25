package pairing

import (
	"crypto/hmac"
	"errors"
	"sync"
	"time"

	"filippo.io/cpace"

	"styli/core/internal/devices"
)

// Phase is where a pairing is.
type Phase string

const (
	Idle      Phase = "idle"      // no pairing open
	Waiting   Phase = "waiting"   // words shown, waiting for a device
	Checking  Phase = "checking"  // a device tried the words; waiting for its proof
	Comparing Phase = "comparing" // the words matched; the person compares the emoji
	Paired    Phase = "paired"
	Failed    Phase = "failed"
	Cancelled Phase = "cancelled"
	Expired   Phase = "expired"
)

// Status is what the atrium's screen (or the device, for its own pairing)
// shows. Words are only included for the atrium's own screen.
type Status struct {
	Phase          Phase     `json:"phase"`
	Words          string    `json:"words,omitempty"`
	Expires        time.Time `json:"expires,omitzero"`
	DeviceName     string    `json:"device_name,omitempty"`
	Emoji          []Symbol  `json:"emoji,omitempty"`
	AtriumApproved bool      `json:"atrium_approved,omitempty"`
	DeviceApproved bool      `json:"device_approved,omitempty"`
	Reason         string    `json:"reason,omitempty"`
}

// Atrium runs pairings on the atrium. One pairing at a time; opening a new one
// ends the last. Safe for concurrent use.
type Atrium struct {
	id   string // the atrium's own key
	devs *devices.List
	now  func() time.Time

	mu sync.Mutex
	s  *session
}

type session struct {
	words    string
	expires  time.Time
	phase    Phase
	deviceID string
	name     string
	key      []byte
	exporter []byte
	atrium   bool // the person approved on the atrium
	device   bool // the person approved on the device
	reason   string
}

// NewAtrium makes the pairing service for an atrium with key atriumID.
func NewAtrium(atriumID string, devs *devices.List) *Atrium {
	return &Atrium{id: atriumID, devs: devs, now: time.Now}
}

// tick moves a session past its deadline to Expired. Call with the lock held.
func (a *Atrium) tick() {
	s := a.s
	if s == nil {
		return
	}
	switch s.phase {
	case Waiting, Checking, Comparing:
		if !a.now().Before(s.expires) {
			s.phase, s.reason = Expired, "The pairing timed out. Start again for new words."
			s.key = nil
		}
	}
}

// end finishes a session that did not pair. Call with the lock held.
func (s *session) end(p Phase, reason string) {
	s.phase, s.reason, s.key = p, reason, nil
}

// Open starts a new pairing with new words, ending any other.
func (a *Atrium) Open() Status {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.s = &session{words: newWords(), expires: a.now().Add(Window), phase: Waiting}
	return a.status()
}

// Status is the pairing as the atrium's screen shows it.
func (a *Atrium) Status() Status {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.tick()
	return a.status()
}

func (a *Atrium) status() Status {
	s := a.s
	if s == nil {
		return Status{Phase: Idle}
	}
	st := Status{Phase: s.phase, DeviceName: s.name, AtriumApproved: s.atrium, DeviceApproved: s.device, Reason: s.reason}
	switch s.phase {
	case Waiting:
		st.Words, st.Expires = s.words, s.expires
	case Checking:
		st.Expires = s.expires
	case Comparing:
		st.Expires, st.Emoji = s.expires, emojiFor(s.exporter, a.id, s.deviceID)
	}
	return st
}

// Admits reports whether the TLS handshake may let in a key that is not
// paired: only while words are waiting to be tried, or for the one device
// whose pairing is in progress. The pairing endpoints still check everything.
func (a *Atrium) Admits(deviceID string) bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.tick()
	s := a.s
	if s == nil {
		return false
	}
	switch s.phase {
	case Waiting:
		return true
	case Checking, Comparing, Paired:
		return s.deviceID == deviceID
	}
	return false
}

// Start is the device's first message: its CPace share and its name. It uses
// up the words, whatever happens next. exporter is taken from the TLS
// connection the request came on. It returns the atrium's share and proof.
func (a *Atrium) Start(deviceID, name string, exporter, msgA []byte) (msgB, atriumProof []byte, err error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.tick()
	s := a.s
	if s == nil || s.phase != Waiting {
		return nil, nil, ErrNoPairing
	}
	// Checks that do not test the words leave them unused.
	for _, d := range a.devs.List() {
		if d.ID == deviceID {
			if d.RevokedAt != nil {
				return nil, nil, ErrRevokedKey
			}
			return nil, nil, ErrKnownKey
		}
	}
	if !ValidName(name) {
		return nil, nil, ErrBadName
	}
	if len(exporter) != ExporterLen {
		return nil, nil, errors.New("pairing: no TLS connection to bind to")
	}

	// From here the words are used up: one try.
	s.phase, s.deviceID, s.name, s.exporter = Checking, deviceID, name, exporter
	msgB, key, err := cpace.Exchange(s.words, contextInfo(deviceID, a.id, exporter), msgA)
	if err != nil {
		s.end(Failed, "The device sent something that was not valid. Start again for new words.")
		return nil, nil, ErrWrongWords
	}
	s.key = key
	return msgB, proof(key, "atrium"), nil
}

// Confirm checks the device's proof. If it matches, the words were right and
// nobody relayed the connection; the emoji are then shown on both sides.
func (a *Atrium) Confirm(deviceID string, deviceProof []byte) ([]Symbol, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.tick()
	s := a.s
	if s == nil || s.phase != Checking || s.deviceID != deviceID {
		return nil, ErrNoPairing
	}
	if !hmac.Equal(deviceProof, proof(s.key, "device")) {
		s.end(Failed, "The words did not match. Start again for new words.")
		return nil, ErrWrongWords
	}
	s.phase, s.expires = Comparing, a.now().Add(Window)
	return emojiFor(s.exporter, a.id, deviceID), nil
}

// Approve records that the person confirmed the emoji match, on the atrium
// (deviceID "") or on the device. Once both have, the device is paired.
func (a *Atrium) Approve(deviceID string) (Status, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.tick()
	s := a.s
	if s == nil || s.phase != Comparing || (deviceID != "" && s.deviceID != deviceID) {
		return a.status(), ErrNoPairing
	}
	if deviceID == "" {
		s.atrium = true
	} else {
		s.device = true
	}
	if s.atrium && s.device {
		if err := a.devs.Add(s.deviceID, s.name); err != nil {
			s.end(Failed, "The device could not be saved: "+err.Error())
			return a.status(), err
		}
		s.phase, s.key = Paired, nil
	}
	return a.status(), nil
}

// Cancel ends the pairing, from the atrium (deviceID "") or the device.
// Nothing is saved.
func (a *Atrium) Cancel(deviceID string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.tick()
	s := a.s
	if s == nil || (deviceID != "" && s.deviceID != deviceID) {
		return
	}
	switch s.phase {
	case Waiting, Checking, Comparing:
		if deviceID == "" {
			s.end(Cancelled, "Pairing was cancelled on the atrium.")
		} else {
			s.end(Cancelled, "Pairing was cancelled on the device.")
		}
	}
}

// DeviceStatus is the pairing as the device in it sees it (no words).
func (a *Atrium) DeviceStatus(deviceID string) (Status, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.tick()
	if a.s == nil || a.s.deviceID != deviceID {
		return Status{}, ErrNoPairing
	}
	st := a.status()
	st.Words = ""
	return st, nil
}
