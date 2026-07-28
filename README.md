# Central File-Sharing Server (Data Consistency & Replication Strategies)

A central file server supporting upload, download, and list, built with
raw Java TCP sockets to demonstrate two distributed-systems concepts:
**replication** and **consistency**.

## What it does

- Clients can **upload** a file to the server.
- Clients can **download** any file the server has.
- Clients can **list** all available files.
- Every upload is automatically written to a primary copy AND a replica
  copy — simulating basic replication.
- If the primary copy is ever missing, the server automatically falls
  back to serving the replica, logging a warning.

This corresponds to the course topic **Data consistency and replication
strategies in Distributed Systems**.

## Design: Replication

```java
Files.copy(primaryPath, replicaPath, StandardCopyOption.REPLACE_EXISTING);
```

Every uploaded file is written to `primary_storage/` and then copied to
`replica_storage/`. This is a simplified, single-machine simulation of
what real distributed databases do across multiple physical servers —
keeping duplicate copies of data so that losing one copy doesn't mean
losing the data entirely.

**Tested and confirmed**: with the primary copy deliberately deleted, a
download request still succeeded — the server detected the missing
primary, logged a warning, and served the replica instead, with the
checksum still matching the original file exactly.

## Design: Consistency (Read/Write Locks)

```java
private static final ConcurrentHashMap<String, ReadWriteLock> fileLocks = ...
```

Each filename gets its own `ReadWriteLock`:
- **Upload** acquires the **write lock** — exclusive. No one else can
  read or write that filename until the upload fully completes.
- **Download** acquires the **read lock** — shared. Multiple clients can
  download the same file simultaneously, but must wait if an upload of
  that exact file is currently in progress.

This guarantees a core consistency property: **no client can ever
download a partially-written, corrupted file.** A download either sees
the complete previous version or waits for the complete new version —
never something in between.

## A real bug this project surfaced (worth knowing for Q&A)

During testing, uploads initially completed with the server reporting
success, but the resulting file was **empty**. The cause: the server
originally used a `BufferedReader` to read the first line (the command),
and `BufferedReader` reads ahead in large chunks for efficiency — it
silently pulled the file's binary bytes into its own internal buffer
along with the command line. A separate `DataInputStream` created
afterward had no way to recover those already-buffered bytes.

**The fix**: read the command line manually, one byte at a time, directly
off the raw socket stream, and reuse that exact same stream reference for
the binary file data afterward — guaranteeing no bytes are silently lost
between two different stream wrappers.

This is a genuinely useful thing to mention if asked about challenges
faced: it shows a real understanding of how buffered I/O can silently
interact badly with raw socket protocols that mix text and binary data.

## How to run

**1. Compile:**
```bash
javac FileServer.java FileClient.java
```

**2. Start the server:**
```bash
java FileServer
```

**3. Use the client:**
```bash
java FileClient upload myfile.txt
java FileClient list
java FileClient download myfile.txt output.txt
```

### Example output

```
$ java FileClient upload notes.txt
Uploaded "notes.txt" (53 bytes).

$ java FileClient list
Files available on server (1):
  - notes.txt

$ java FileClient download notes.txt downloaded_notes.txt
Downloaded "notes.txt" (53 bytes) -> downloaded_notes.txt
```

Server console:
```
[/127.0.0.1:34892] Uploaded and replicated: notes.txt (53 bytes)
[/127.0.0.1:34906] Downloaded: notes.txt
```

## Verified correctness

- MD5 checksums matched across: original file, primary storage, replica
  storage, and the downloaded copy — zero corruption.
- Replica fallback tested by deliberately deleting the primary copy;
  download still succeeded from the replica with a matching checksum.

## Tech stack

- Java 21+ (no external dependencies)

## Where this fits in the larger project

This becomes the app's **shared/cloud storage** feature — a central
place for media, documents, or shared files, separate from the
point-to-point transfer in the previous project. The read/write lock
pattern here is also directly reusable if the unified app later needs
to protect any other shared server-side resource from concurrent
corruption.
