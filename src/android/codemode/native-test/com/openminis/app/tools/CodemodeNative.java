package com.openminis.app.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Host-JVM integration tests of the production C JNI bridge and native engine (no JS mocks). */
public final class CodemodeNative {
    static { System.loadLibrary("codemode_jni"); }
    private static byte[] prelude;
    private final List<String> events = new ArrayList<>();
    private CountDownLatch started;
    public void onEvent(byte[] event) {
        String json = new String(event, StandardCharsets.UTF_8);
        events.add(json);
        if (started != null && json.contains("\"type\":\"output\"")) started.countDown();
    }
    public native long create(byte[] prelude, byte[] tools, byte[] globals, byte[] store);
    public native void start(long handle, byte[] code);
    public native void settle(long handle, int id, boolean ok, byte[] payload);
    public native void interrupt(long handle);
    public native void release(long handle);
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private long create() { return create(prelude,
        bytes("[{\"name\":\"echo\",\"jsName\":\"echo\",\"description\":\"Echo\"}]"),
        bytes("[{\"name\":\"describeTool\",\"spread\":true}]"), bytes("{\"saved\":\"{\\\"value\\\":7}\"}")); }
    private void startScript(long handle, String code) { start(handle, bytes("(async (tools, console) => {" + code + "\n})")); }
    private static void check(boolean test, String message) { if (!test) throw new AssertionError(message); }
    private String last() { return events.isEmpty() ? "" : events.get(events.size() - 1); }
    private void success() { check(last().contains("\"ok\":true"), events.toString()); }
    private void failure() { check(last().contains("\"ok\":false") || last().contains("\"type\":\"crash\""), events.toString()); }
    private static CodemodeNative run(String code) {
        CodemodeNative vm = new CodemodeNative(); long handle = vm.create();
        try { vm.startScript(handle, code); return vm; } finally { vm.release(handle); }
    }
    private static int count;
    private static void test(String name, Checked action) throws Exception {
        action.run(); count++; System.out.println("PASS " + name);
    }
    private interface Checked { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        prelude = Files.readAllBytes(Path.of(args[0]));
        test("UTF-8 console, output, return and store", () -> {
            CodemodeNative vm = run("console.log('你好🌍'); text('完成🌍'); store('a', {n: 1}); return load('saved').value");
            vm.success(); check(vm.events.toString().contains("你好🌍"), vm.events.toString());
            check(vm.last().contains("\"value\":\"7\""), vm.last());
            check(vm.last().contains("writes"), vm.last());
        });
        test("parallel tool promises, rejection and successful exit", () -> {
            CodemodeNative vm = new CodemodeNative(); long handle = vm.create();
            try {
                vm.startScript(handle, "const p = await Promise.all([tools.echo({a: 1}), tools.echo({a: 2}).catch(e => e.message)]); text(p); exit('done')");
                check(vm.events.size() == 2, vm.events.toString());
                vm.settle(handle, 2, false, bytes("permission denied"));
                check(vm.events.size() == 2, vm.events.toString());
                vm.settle(handle, 1, true, bytes("\"hello🌍\""));
                vm.success(); check(vm.events.toString().contains("permission denied"), vm.events.toString());
                check(vm.events.toString().contains("hello🌍"), vm.events.toString());
            } finally { vm.release(handle); }
        });
        test("discovery globals settle through same bridge", () -> {
            CodemodeNative vm = new CodemodeNative(); long handle = vm.create();
            try {
                vm.startScript(handle, "return await describeTool('echo')");
                check(vm.last().contains("\"target\":\"global\""), vm.last());
                vm.settle(handle, 1, true, bytes("\"echo(options): Promise<string>\""));
                vm.success(); check(vm.last().contains("Promise<string>"), vm.last());
            } finally { vm.release(handle); }
        });
        test("partial output preserved and failed writes discarded", () -> {
            CodemodeNative vm = run("text('partial'); store('x', 1); throw new Error('boom')");
            vm.failure(); check(vm.events.toString().contains("partial"), vm.events.toString());
            check(vm.last().contains("boom") && !vm.last().contains("writes"), vm.last());
        });
        test("stalled promises terminate", () -> { CodemodeNative vm = run("await new Promise(() => {})"); vm.failure(); });
        test("native stack guard is catchable", () -> {
            CodemodeNative vm = run("function recur() { recur() } try { recur() } catch(e) { return e instanceof RangeError }");
            vm.success(); check(vm.last().contains("\"value\":\"true\""), vm.last());
        });
        test("no OS, WebView or JNI capabilities exposed", () -> {
            CodemodeNative vm = run("return [typeof std, typeof os, typeof fetch, typeof WebAssembly, typeof bridge, typeof java, typeof process, typeof require].every(t => t === 'undefined')");
            vm.success(); check(vm.last().contains("\"value\":\"true\""), vm.last());
        });
        test("invalid image URL rejected", () -> { CodemodeNative vm = run("image('https://example.com/x.png')"); vm.failure(); });
        test("syntax failure includes error header and settles transport", () -> {
            CodemodeNative vm = run("return ("); vm.failure(); check(vm.last().contains("SyntaxError"), vm.last());
        });
        test("undefined settlement round trip", () -> {
            CodemodeNative vm = new CodemodeNative(); long handle = vm.create();
            try {
                vm.startScript(handle, "return (await tools.echo({})) === undefined");
                vm.settle(handle, 1, true, null);
                vm.success(); check(vm.last().contains("\"value\":\"true\""), vm.last());
            } finally { vm.release(handle); }
        });
        test("image data and MIME reach native host", () -> {
            String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aGCEAAAAASUVORK5CYII=";
            CodemodeNative vm = run("image('data:image/png;base64," + png + "')"); vm.success();
            check(vm.events.toString().contains("\"mimeType\":\"image/png\""), vm.events.toString());
            check(vm.events.toString().contains(png), vm.events.toString());
        });
        test("JSON preserves lone-surrogate escape", () -> {
            CodemodeNative vm = run("return '\\ud800'"); vm.success();
            check(vm.last().contains("ud800"), vm.last());
        });
        test("Pi store size bound", () -> { CodemodeNative vm = run("store('big', 'x'.repeat(262145))"); vm.failure(); });
        test("Pi output size bound", () -> { CodemodeNative vm = run("text('x'.repeat(16 * 1024 * 1024 + 1))"); vm.failure(); });
        test("native memory limit reports error without crashing host", () -> {
            CodemodeNative vm = run("const held = []; while (true) held.push('x'.repeat(1024 * 1024))"); vm.failure();
        });
        test("bridge OOM is fatal even if script catches it", () -> {
            CodemodeNative vm = run("const big = 'x'.repeat(512*1024); const held = []; " +
                "try { while(true) held.push(new ArrayBuffer(1024*1024)) } catch(e) {} " +
                "try { text(big) } catch(e) { while(true) {} }");
            vm.failure(); check(vm.last().contains("\"type\":\"crash\""), vm.last());
        });
        test("interrupt busy script from another thread", () -> {
            CodemodeNative vm = new CodemodeNative(); vm.started = new CountDownLatch(1);
            AtomicLong handle = new AtomicLong(); AtomicReference<Throwable> error = new AtomicReference<>();
            Thread owner = new Thread(() -> {
                try { handle.set(vm.create()); vm.startScript(handle.get(), "text('started'); while (true) {}"); }
                catch (Throwable e) { error.set(e); }
                finally { if (handle.get() != 0) vm.release(handle.get()); }
            });
            owner.setDaemon(true); owner.start();
            check(vm.started.await(5, TimeUnit.SECONDS), "VM failed to start");
            vm.interrupt(handle.get()); owner.join(5000);
            check(!owner.isAlive(), "Busy VM did not stop"); check(error.get() == null, "JNI exception: " + error.get());
        });
        test("interrupt busy Promise microtask loop", () -> {
            CodemodeNative vm = new CodemodeNative(); vm.started = new CountDownLatch(1);
            AtomicLong handle = new AtomicLong(); AtomicReference<Throwable> error = new AtomicReference<>();
            Thread owner = new Thread(() -> {
                try { handle.set(vm.create()); vm.startScript(handle.get(), "text('started'); while (true) await Promise.resolve()"); }
                catch (Throwable e) { error.set(e); }
                finally { if (handle.get() != 0) vm.release(handle.get()); }
            });
            owner.setDaemon(true); owner.start();
            check(vm.started.await(5, TimeUnit.SECONDS), "VM failed to start");
            vm.interrupt(handle.get()); owner.join(5000);
            check(!owner.isAlive(), "Microtasks did not stop"); check(error.get() == null, "JNI exception: " + error.get());
        });
        System.out.println(count + " native JNI tests passed");
    }
}
