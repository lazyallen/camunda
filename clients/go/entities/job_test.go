package entities

import (
	"github.com/zeebe-io/zeebe/clients/go/pb"
	"reflect"
	"testing"
)

type MyType struct {
	Foo   string
	Hello string
}

var (
	expectedJson      = "{\"foo\": \"bar\", \"hello\": \"world\"}"
	expectedJsonAsMap = map[string]string{
		"foo":   "bar",
		"hello": "world",
	}
	expectedJsonAsStruct = MyType{
		Foo:   "bar",
		Hello: "world",
	}
	job = Job{pb.ActivatedJob{
		CustomHeaders: expectedJson,
		Payload:       expectedJson,
	}}
)

func TestJob_GetPayloadAsMap(t *testing.T) {
	payload, err := job.GetPayloadAsMap()
	if err != nil {
		t.Error("Failed to get payload as map", err)
	}
	if reflect.DeepEqual(payload, expectedJsonAsMap) {
		t.Error("Failed to get payload as map, got", payload, "instead of", expectedJsonAsMap)
	}
}

func TestJob_GetPayloadAs(t *testing.T) {
	var payload MyType
	if err := job.GetPayloadAs(&payload); err != nil {
		t.Error("Failed to get payload as struct", err)
	}
	if payload != expectedJsonAsStruct {
		t.Error("Failed to get payload as struct, got", payload, "instead of", expectedJsonAsStruct)
	}
}

func TestJob_GetCustomHeadersAsMap(t *testing.T) {
	customHeaders, err := job.GetCustomHeadersAsMap()
	if err != nil {
		t.Error("Failed to get custom headers as map", err)
	}
	if reflect.DeepEqual(customHeaders, expectedJsonAsMap) {
		t.Error("Failed to get custom headers as map, got", customHeaders, "instead of", expectedJsonAsMap)
	}
}

func TestJob_GetCustomHeadersAs(t *testing.T) {
	var customHeaders MyType
	if err := job.GetCustomHeadersAs(&customHeaders); err != nil {
		t.Error("Failed to get custom headers as struct", err)
	}
	if customHeaders != expectedJsonAsStruct {
		t.Error("Failed to get custom headers as struct, got", customHeaders, "instead of", expectedJsonAsStruct)
	}
}
