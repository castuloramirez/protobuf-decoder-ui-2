# protobuf-decoder-ui (protobufpal replacement)

A tiny local web UI (single fat JAR) that decodes **Base64-encoded protobuf messages** using a **descriptor set** (`FileDescriptorSet`) and `DynamicMessage`.

## 1) Create a descriptor set

From your `.proto` folder:

```bash
protoc --include_imports \
  --descriptor_set_out=all.desc \
  -I path/to/protos \
  path/to/protos/*.proto
```

Put the resulting `all.desc` here:

```
src/main/resources/protos/all.desc
```

> If your protos import google well-known types or other includes, `--include_imports` is important.

## 2) Build and run

```bash
mvn -q clean package
java -jar target/protobuf-decoder-ui-1.0.0.jar
```

Open:

```
http://localhost:7070
```

### Use a descriptor file path at runtime

```bash
java -jar target/protobuf-decoder-ui-1.0.0.jar --desc /path/to/all.desc --port 7071
```

## Notes

If you get errors like **"invalid wire type"** or **"truncated message"**, the payload is often not "raw protobuf".
Common wrappers:

- gRPC framing (5-byte header)
- delimited protobuf (varint length prefix)
- application-specific envelope/header

The UI supports `AUTO`, `RAW`, `GRPC`, and `DELIMITED_VARINT`.

If you paste one failing Base64 and tell me the expected message type, I can show how to strip your custom header or decode an envelope first.

## Base on

https://www.protobufpal.com/


## HTML UI

```
http://localhost:7070
```


## REACT UI