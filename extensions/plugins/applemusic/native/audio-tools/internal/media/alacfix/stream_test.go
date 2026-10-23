package alacfix

import (
	"bytes"
	"testing"
)

func TestPacketPatcherValidatesConfig(t *testing.T) {
	if _, err := PacketPatcher([]byte("invalid")); err == nil {
		t.Fatal("expected invalid entry error")
	}
	entry := makeAtom("alac", append(make([]byte, 28), makeAlacMagicCookie()...))
	patch, err := PacketPatcher(entry)
	if err != nil {
		t.Fatal(err)
	}
	// A packet containing only TYPE_END already needs no changes.
	packet := createTestPacket(true)
	before := append([]byte(nil), packet...)
	patch(packet)
	if !bytes.Equal(packet, before) {
		t.Fatal("valid packet changed")
	}
	bad := createTestPacket(false)
	patch(bad)
	if !bytes.Equal(bad, packet) {
		t.Fatal("malformed packet did not receive identical repair")
	}
	patch([]byte{})
	patch([]byte{0xff})
}
