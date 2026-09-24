package tailcatbridge

import (
	"bufio"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"testing"
)

func TestSendFileContentsResumesAndRequiresAck(t *testing.T) {
	data := []byte("0123456789abcdefghijklmnopqrstuvwxyz")
	f, err := os.CreateTemp(t.TempDir(), "tailcat-resume-*")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if _, err := f.Write(data); err != nil {
		t.Fatal(err)
	}

	sender, receiver := net.Pipe()
	done := make(chan error, 1)
	go func() {
		defer sender.Close()
		done <- sendFileContents(sender, f, int64(len(data)), nil, nil)
	}()

	const offset = 10
	if _, err := io.WriteString(receiver, "GALLERYPLUS/1 RESUME "+strconv.Itoa(offset)+"\n"); err != nil {
		t.Fatal(err)
	}
	reader := bufio.NewReader(receiver)
	header, err := reader.ReadString('\n')
	if err != nil {
		t.Fatal(err)
	}
	if got, want := strings.TrimSpace(header), "GALLERYPLUS/1 FILE 36 10"; got != want {
		t.Fatalf("header = %q, want %q", got, want)
	}
	received := make([]byte, len(data)-offset)
	if _, err := io.ReadFull(reader, received); err != nil {
		t.Fatal(err)
	}
	if got, want := string(received), string(data[offset:]); got != want {
		t.Fatalf("payload = %q, want %q", got, want)
	}
	if _, err := io.WriteString(receiver, "GALLERYPLUS/1 OK 36\n"); err != nil {
		t.Fatal(err)
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestSendFileContentsRejectsMissingCompletionAck(t *testing.T) {
	f, err := os.CreateTemp(t.TempDir(), "tailcat-no-ack-*")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if _, err := f.WriteString("payload"); err != nil {
		t.Fatal(err)
	}

	sender, receiver := net.Pipe()
	done := make(chan error, 1)
	go func() {
		defer sender.Close()
		done <- sendFileContents(sender, f, 7, nil, nil)
	}()
	if _, err := io.WriteString(receiver, "GALLERYPLUS/1 RESUME 0\n"); err != nil {
		t.Fatal(err)
	}
	reader := bufio.NewReader(receiver)
	if _, err := reader.ReadString('\n'); err != nil {
		t.Fatal(err)
	}
	received := make([]byte, 7)
	if _, err := io.ReadFull(reader, received); err != nil {
		t.Fatal(err)
	}
	_ = receiver.Close()
	if err := <-done; err == nil || !strings.Contains(err.Error(), "receiver confirmation") {
		t.Fatalf("error = %v, want receiver confirmation failure", err)
	}
}
