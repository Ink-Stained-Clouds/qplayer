package io.github.timer_err.qml4j.android;

import com.android.tools.r8.ByteDataView;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.DexIndexedConsumer;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.origin.Origin;

import dalvik.system.InMemoryDexClassLoader;

import io.github.timer_err.qml4j.engine.classloader.ClassLoaderBackend;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DexClassLoaderBackend implements ClassLoaderBackend {

    private ClassLoader parent;
    private final int minApi;

    public DexClassLoaderBackend(ClassLoader parent) {
        this(parent, 26);
    }

    public DexClassLoaderBackend(ClassLoader parent, int minApi) {
        this.parent = parent;
        this.minApi = minApi;
    }

    @Override
    public Class<?> defineClass(String name, byte[] jvmBytecode) {
        Map<String, byte[]> one = new LinkedHashMap<>();
        one.put(name, jvmBytecode);
        return defineClasses(one).get(name);
    }

    @Override
    public Map<String, Class<?>> defineClasses(Map<String, byte[]> classes) {
        if (classes.isEmpty()) return new LinkedHashMap<>();
        byte[] dex = dexAll(classes);
        ByteBuffer buf = ByteBuffer.wrap(dex);
        InMemoryDexClassLoader loader = new InMemoryDexClassLoader(buf, parent);
        Map<String, Class<?>> out = new LinkedHashMap<>();
        try {
            for (String name : classes.keySet()) {
                out.put(name, loader.loadClass(name));
            }
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        parent = loader;
        return out;
    }

    private byte[] dexAll(Map<String, byte[]> classes) {
        D8Command.Builder b = D8Command.builder();
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            b.addClassProgramData(e.getValue(), Origin.unknown());
        }
        b.setMinApiLevel(minApi);
        final byte[][] out = new byte[1][];
        b.setProgramConsumer(new DexIndexedConsumer.ForwardingConsumer(null) {
            @Override
            public void accept(int fileIndex,
                               ByteDataView data,
                               java.util.Set<String> descriptors,
                               DiagnosticsHandler handler) {
                if (out[0] != null) {
                    throw new IllegalStateException(
                        "D8 produced multiple dex files; expected single");
                }
                out[0] = data.copyByteData();
            }
        });
        try {
            D8.run(b.build());
        } catch (Exception ex) {
            throw new RuntimeException("D8 dexing failed", ex);
        }
        if (out[0] == null) {
            throw new IllegalStateException("D8 produced no dex output");
        }
        return out[0];
    }
}
