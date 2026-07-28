package dev.t1m3.qplayer.desktop;

import com.sun.jna.CallbackReference;
import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.ptr.ByteByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

import dev.t1m3.qplayer.bridge.PlayerController;
import dev.t1m3.qplayer.model.Track;
import dev.t1m3.qplayer.util.Logger;

import org.lwjgl.glfw.GLFWNativeWin32;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Windows 10/11 System Media Transport Controls (SMTC), projected directly from
 * WinRT through JNA. GetForWindow binds the session to QPlayer's own HWND, so the
 * flyout and hardware media keys identify the real application rather than a
 * helper process.
 */
final class WindowsMediaControls implements DesktopMediaControls {
    private static final int S_OK = 0;
    private static final int E_NOINTERFACE = 0x80004002;
    private static final int RO_INIT_MULTITHREADED = 1;

    private static final GUID IID_INTEROP =
            guid("ddb0472d-c911-4a1f-86d9-dc3d71a95f5a");
    private static final GUID IID_SMTC =
            guid("99fa3ff4-1742-42a6-902e-087d41f965ec");
    private static final GUID IID_SMTC2 =
            guid("ea98d2f6-7f3c-4af2-a586-72889808efb1");
    private static final GUID IID_URI_FACTORY =
            guid("44a9796f-723e-4fdf-a218-033e75b0c084");
    private static final GUID IID_STREAM_REFERENCE_STATICS =
            guid("857309dc-3fbf-4e7d-986f-ef3b1a07a964");

    interface Combase extends Library {
        int RoInitialize(int initType);
        int WindowsCreateString(WString source, int length, PointerByReference value);
        int WindowsDeleteString(Pointer value);
        int RoGetActivationFactory(Pointer classId, GUID iid, PointerByReference factory);
        int RoActivateInstance(Pointer classId, PointerByReference instance);
    }

    interface QueryInterfaceCallback extends StdCallLibrary.StdCallCallback {
        int invoke(Pointer self, Pointer iid, PointerByReference object);
    }
    interface RefCallback extends StdCallLibrary.StdCallCallback {
        int invoke(Pointer self);
    }
    interface InspectCallback extends StdCallLibrary.StdCallCallback {
        int invoke(Pointer self, Pointer value);
    }
    interface GetIidsCallback extends StdCallLibrary.StdCallCallback {
        int invoke(Pointer self, IntByReference count, PointerByReference iids);
    }
    interface InvokeCallback extends StdCallLibrary.StdCallCallback {
        int invoke(Pointer self, Pointer sender, Pointer args);
    }

    private final PlayerController controller;
    private final DesktopWindow window;
    private final List<Object> keepAlive = new ArrayList<>();

    private Combase combase;
    private Pointer smtc;
    private Pointer smtc2;
    private Pointer timeline;
    private volatile boolean running;
    private volatile long pausedPositionMs;

    WindowsMediaControls(PlayerController controller, DesktopWindow window) {
        this.controller = controller;
        this.window = window;
    }

    @Override
    public void start() {
        try {
            combase = Native.load("combase", Combase.class);
            int init = combase.RoInitialize(RO_INIT_MULTITHREADED);
            if (failed(init) && init != 0x80010106) check(init, "RoInitialize");

            Pointer factory = activationFactory(
                    "Windows.Media.SystemMediaTransportControls", IID_INTEROP);
            PointerByReference out = new PointerByReference();
            long hwnd = GLFWNativeWin32.glfwGetWin32Window(window.window());
            check(call(factory, 6, Pointer.createConstant(hwnd), IID_SMTC.getPointer(), out),
                    "ISystemMediaTransportControlsInterop.GetForWindow");
            smtc = out.getValue();
            release(factory);
            smtc2 = query(smtc, IID_SMTC2);

            enableButtons();
            installHandlers();
            timeline = activate(
                    "Windows.Media.SystemMediaTransportControlsTimelineProperties");
            running = true;
            publish();
            Logger.info("system media controls initialized: Windows SMTC");
        } catch (Throwable t) {
            Logger.warn("Windows system media controls unavailable: {}", t);
            shutdown();
        }
    }

    @Override
    public void shutdown() {
        running = false;
        try {
            if (smtc != null) {
                call(smtc, 7, 0);  // Closed
                call(smtc, 11, (byte) 0);
                release(smtc);
            }
            if (smtc2 != null) release(smtc2);
            if (timeline != null) release(timeline);
        } catch (Throwable ignored) {
        }
        smtc = null;
        smtc2 = null;
        timeline = null;
        keepAlive.clear();
    }

    @Override
    public void onPlaybackChanged() {
        if (!controller.isPlaying()) pausedPositionMs = Math.max(0L, controller.position());
        publish();
    }

    private void enableButtons() {
        check(call(smtc, 11, (byte) 1), "SetIsEnabled");
        check(call(smtc, 13, (byte) 1), "SetIsPlayEnabled");
        check(call(smtc, 17, (byte) 1), "SetIsPauseEnabled");
        check(call(smtc, 25, (byte) 1), "SetIsPreviousEnabled");
        check(call(smtc, 27, (byte) 1), "SetIsNextEnabled");
    }

    private void installHandlers() {
        Pointer buttonHandler = handler((sender, args) -> {
            IntByReference button = new IntByReference();
            check(call(args, 6, button), "Button");
            switch (button.getValue()) {
                case 0:
                    if (!controller.isPlaying()) window.postMainTask(controller::toggle);
                    break;
                case 1:
                case 2:
                    if (controller.isPlaying()) window.postMainTask(controller::toggle);
                    break;
                case 6:
                    window.postMainTask(controller::next);
                    break;
                case 7:
                    window.postMainTask(controller::prev);
                    break;
                default:
                    break;
            }
        });
        check(call(smtc, 30, buttonHandler, new LongByReference()), "ButtonPressed");

        if (smtc2 == null) return;
        Pointer seekHandler = handler((sender, args) -> {
            LongByReference ticks = new LongByReference();
            check(call(args, 6, ticks), "RequestedPlaybackPosition");
            long ms = Math.max(0L, ticks.getValue() / 10_000L);
            window.postMainTask(() -> {
                controller.seek(ms);
                pausedPositionMs = ms;
                publish();
            });
        });
        check(call(smtc2, 13, seekHandler, new LongByReference()),
                "PlaybackPositionChangeRequested");

        Pointer shuffleHandler = handler((sender, args) -> {
            ByteByReference enabled = new ByteByReference();
            check(call(args, 6, enabled), "RequestedShuffleEnabled");
            window.postMainTask(() -> {
                if (enabled.getValue() != 0) controller.setPlayMode(1);
                else if (playMode() == 1) controller.setPlayMode(0);
            });
        });
        check(call(smtc2, 17, shuffleHandler, new LongByReference()),
                "ShuffleEnabledChangeRequested");

        Pointer repeatHandler = handler((sender, args) -> {
            IntByReference mode = new IntByReference();
            check(call(args, 6, mode), "RequestedAutoRepeatMode");
            window.postMainTask(() -> controller.setPlayMode(
                    mode.getValue() == 1 ? 2 : 0));
        });
        check(call(smtc2, 19, repeatHandler, new LongByReference()),
                "AutoRepeatModeChangeRequested");
    }

    private interface EventBody {
        void invoke(Pointer sender, Pointer args);
    }

    /** Minimal WinRT TypedEventHandler COM object. */
    private Pointer handler(EventBody body) {
        AtomicInteger refs = new AtomicInteger(1);
        Memory object = new Memory(Native.POINTER_SIZE);
        Memory vtable = new Memory((long) Native.POINTER_SIZE * 7);

        QueryInterfaceCallback qi = (self, iid, out) -> {
            out.setValue(self);
            refs.incrementAndGet();
            return S_OK;
        };
        RefCallback addRef = self -> refs.incrementAndGet();
        RefCallback release = self -> Math.max(0, refs.decrementAndGet());
        GetIidsCallback getIids = (self, count, iids) -> {
            if (count != null) count.setValue(0);
            if (iids != null) iids.setValue(Pointer.NULL);
            return S_OK;
        };
        InspectCallback runtimeName = (self, value) -> E_NOINTERFACE;
        InspectCallback trustLevel = (self, value) -> {
            if (value != null) value.setInt(0, 0);
            return S_OK;
        };
        InvokeCallback invoke = (self, sender, args) -> {
            try {
                body.invoke(sender, args);
                return S_OK;
            } catch (Throwable t) {
                Logger.warn("Windows media command failed: {}", t);
                return 0x80004005;
            }
        };
        Object[] callbacks = {qi, addRef, release, getIids, runtimeName, trustLevel, invoke};
        for (int i = 0; i < callbacks.length; i++) {
            vtable.setPointer((long) i * Native.POINTER_SIZE,
                    CallbackReference.getFunctionPointer((com.sun.jna.Callback) callbacks[i]));
        }
        object.setPointer(0, vtable);
        keepAlive.add(object);
        keepAlive.add(vtable);
        java.util.Collections.addAll(keepAlive, callbacks);
        return object;
    }

    private void publish() {
        if (!running || smtc == null) return;
        try {
            Track track = controller.currentTrack();
            if (track == null) {
                call(smtc, 7, 0);
                return;
            }
            updateMetadata(track);
            call(smtc, 7, controller.isPlaying() ? 3 : 4);

            if (smtc2 != null) {
                int mode = playMode();
                call(smtc2, 7, mode == 2 ? 1 : 2); // Track / List
                call(smtc2, 9, (byte) (mode == 1 ? 1 : 0));
                updateTimeline(track);
            }
        } catch (Throwable t) {
            Logger.warn("Windows SMTC update failed: {}", t);
        }
    }

    private void updateMetadata(Track track) {
        PointerByReference updaterOut = new PointerByReference();
        check(call(smtc, 8, updaterOut), "DisplayUpdater");
        Pointer updater = updaterOut.getValue();
        try {
            call(updater, 16);       // ClearAll
            call(updater, 7, 1);     // MediaPlaybackType.Music
            PointerByReference musicOut = new PointerByReference();
            check(call(updater, 12, musicOut), "MusicProperties");
            Pointer music = musicOut.getValue();
            try {
                setHString(music, 7, track.title);
                setHString(music, 11, track.artist);
                Pointer music2 = query(music,
                        guid("00368462-97d3-44b9-b00f-008afcefaf18"));
                if (music2 != null) {
                    try {
                        setHString(music2, 7, track.album);
                    } finally {
                        release(music2);
                    }
                }
            } finally {
                release(music);
            }
            Pointer thumbnail = remoteThumbnail(track.coverUrl);
            if (thumbnail != null) {
                try {
                    check(call(updater, 11, thumbnail), "SetThumbnail");
                } finally {
                    release(thumbnail);
                }
            }
            check(call(updater, 17), "DisplayUpdater.Update");
        } finally {
            release(updater);
        }
    }

    private Pointer remoteThumbnail(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return null;
        }
        Pointer uriFactory = null;
        Pointer streamFactory = null;
        Pointer uri = null;
        try {
            uriFactory = activationFactory("Windows.Foundation.Uri", IID_URI_FACTORY);
            Pointer text = hstring(url);
            PointerByReference uriOut = new PointerByReference();
            try {
                check(call(uriFactory, 6, text, uriOut), "Uri.CreateUri");
            } finally {
                combase.WindowsDeleteString(text);
            }
            uri = uriOut.getValue();
            streamFactory = activationFactory(
                    "Windows.Storage.Streams.RandomAccessStreamReference",
                    IID_STREAM_REFERENCE_STATICS);
            PointerByReference streamOut = new PointerByReference();
            check(call(streamFactory, 7, uri, streamOut),
                    "RandomAccessStreamReference.CreateFromUri");
            return streamOut.getValue();
        } catch (Throwable t) {
            Logger.warn("Windows SMTC artwork unavailable: {}", t.getMessage());
            return null;
        } finally {
            release(uri);
            release(uriFactory);
            release(streamFactory);
        }
    }

    private void updateTimeline(Track track) {
        if (timeline == null) return;
        long durationMs = track.durationMs > 0 ? track.durationMs : controller.duration();
        long durationTicks = durationMs * 10_000L;
        long positionTicks = positionMs() * 10_000L;
        call(timeline, 7, 0L);                // StartTime
        call(timeline, 9, durationTicks);     // EndTime
        call(timeline, 11, 0L);               // MinSeekTime
        call(timeline, 13, durationTicks);    // MaxSeekTime
        call(timeline, 15, positionTicks);    // Position
        check(call(smtc2, 12, timeline), "UpdateTimelineProperties");
    }

    private long positionMs() {
        return controller.isPlaying() ? Math.max(0L, controller.position()) : pausedPositionMs;
    }

    private int playMode() {
        Integer value = controller.playMode.peek();
        return value != null ? value : 0;
    }

    private void setHString(Pointer object, int method, String value) {
        if (value == null) value = "";
        Pointer hstring = hstring(value);
        try {
            check(call(object, method, hstring), "set metadata string");
        } finally {
            combase.WindowsDeleteString(hstring);
        }
    }

    private Pointer activationFactory(String className, GUID iid) {
        Pointer name = hstring(className);
        try {
            PointerByReference out = new PointerByReference();
            check(combase.RoGetActivationFactory(name, iid, out), "RoGetActivationFactory");
            return out.getValue();
        } finally {
            combase.WindowsDeleteString(name);
        }
    }

    private Pointer activate(String className) {
        Pointer name = hstring(className);
        try {
            PointerByReference out = new PointerByReference();
            check(combase.RoActivateInstance(name, out), "RoActivateInstance");
            return out.getValue();
        } finally {
            combase.WindowsDeleteString(name);
        }
    }

    private Pointer hstring(String text) {
        PointerByReference out = new PointerByReference();
        check(combase.WindowsCreateString(new WString(text), text.length(), out),
                "WindowsCreateString");
        return out.getValue();
    }

    private static Pointer query(Pointer object, GUID iid) {
        PointerByReference out = new PointerByReference();
        int hr = call(object, 0, iid.getPointer(), out);
        return failed(hr) ? null : out.getValue();
    }

    private static void release(Pointer object) {
        if (object != null) call(object, 2);
    }

    private static int call(Pointer object, int method, Object... args) {
        if (object == null) return E_NOINTERFACE;
        Pointer vtable = object.getPointer(0);
        Pointer address = vtable.getPointer((long) method * Native.POINTER_SIZE);
        Object[] all = new Object[args.length + 1];
        all[0] = object;
        System.arraycopy(args, 0, all, 1, args.length);
        return (Integer) Function.getFunction(address, Function.ALT_CONVENTION)
                .invoke(int.class, all);
    }

    private static boolean failed(int hr) {
        return hr < 0;
    }

    private static void check(int hr, String operation) {
        if (failed(hr)) {
            throw new IllegalStateException(operation + " failed: 0x"
                    + Integer.toHexString(hr));
        }
    }

    private static GUID guid(String value) {
        GUID guid = new GUID(value);
        guid.write();
        return guid;
    }
}
