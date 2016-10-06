package com.github.davidmoten.rx;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import com.github.davidmoten.rx.exceptions.IORuntimeException;

import rx.Observable;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.plugins.RxJavaHooks;

//TODO as suggested by George Campbell, may replace with his PR
public class ResourceManager<T> {

    private final Func0<T> resourceFactory;
    private final Action1<? super T> disposeAction;
    private final boolean disposeEagerly;

    protected ResourceManager(Func0<T> resourceFactory, Action1<? super T> disposeAction,
            boolean disposeEagerly) {
        this.resourceFactory = resourceFactory;
        this.disposeAction = disposeAction;
        this.disposeEagerly = disposeEagerly;
    }

    public static <T> ResourceManagerBuilder<T> resourceFactory(Func0<T> resourceFactory) {
        return new ResourceManagerBuilder<T>(resourceFactory);
    }

    public final static class ResourceManagerBuilder<T> {

        private final Func0<T> resourceFactory;
        private boolean disposeEagerly = false;

        private ResourceManagerBuilder(Func0<T> resourceFactory) {
            this.resourceFactory = resourceFactory;
        }

        public ResourceManagerBuilder<T> disposeEagerly(boolean value) {
            this.disposeEagerly = value;
            return this;
        }

        public ResourceManager<T> disposeAction(Action1<? super T> disposeAction) {
            return new ResourceManager<T>(resourceFactory, disposeAction, disposeEagerly);
        }

    }

    public static <T> ResourceManager<T> create(Func0<T> resourceFactory,
            Action1<? super T> disposeAction) {
        return new ResourceManager<T>(resourceFactory, disposeAction, false);
    }

    public static <T> ResourceManager<T> create(Callable<T> resourceFactory,
            Action1<? super T> disposeAction) {
        return new ResourceManager<T>(Functions.toFunc0(resourceFactory), disposeAction, false);
    }

    public static <T> ResourceManager<T> create(Callable<T> resourceFactory,
            Checked.A1<? super T> disposeAction) {
        return new ResourceManager<T>(Functions.toFunc0(resourceFactory), Checked.a1(disposeAction),
                false);
    }

    public static <T extends Closeable> ResourceManager<T> create(Func0<T> resourceFactory) {
        return create(resourceFactory, CloserHolder.INSTANCE);
    }

    public static <T extends Closeable> ResourceManager<T> create(Callable<T> resourceFactory) {
        return create(Functions.toFunc0(resourceFactory), CloserHolder.INSTANCE);
    }

    public static ResourceManager<OutputStream> forOutput(final File file) {
        Func0<OutputStream> rf = new Func0<OutputStream>() {

            @Override
            public OutputStream call() {
                try {
                    return new FileOutputStream(file);
                } catch (FileNotFoundException e) {
                    throw new IORuntimeException(e);
                }
            }
        };
        return create(rf);
    }

    public static ResourceManager<PrintWriter> forWriting(final File file, final Charset charset) {
        Func0<PrintWriter> rf = new Func0<PrintWriter>() {

            @Override
            public PrintWriter call() {
                try {
                    return new PrintWriter(new BufferedWriter(
                            new OutputStreamWriter(new FileOutputStream(file), charset)));
                } catch (FileNotFoundException e) {
                    throw new IORuntimeException(e);
                }
            }
        };
        return create(rf);
    }

    public static ResourceManager<InputStream> forInput(final File file) {
        Func0<InputStream> rf = new Func0<InputStream>() {

            @Override
            public InputStream call() {
                try {
                    return new FileInputStream(file);
                } catch (FileNotFoundException e) {
                    throw new IORuntimeException(e);
                }
            }
        };
        return create(rf);
    }

    public <R> Observable<R> observable(
            Func1<? super T, ? extends Observable<? extends R>> observableFactory) {
        return Observable.using(resourceFactory, observableFactory, disposeAction, disposeEagerly);
    }

    public <R> ResourceManager<R> map(final Checked.F1<? super T, ? extends R> resourceMapper,
            final Checked.A1<? super R> disposeAction) {
        return map(Checked.f1(resourceMapper), Checked.a1(disposeAction));
    }

    public <R> ResourceManager<R> map(final Func1<? super T, ? extends R> resourceMapper,
            final Action1<? super R> disposeAction) {
        final AtomicReference<T> ref = new AtomicReference<T>();
        Func0<R> rf = new Func0<R>() {
            @Override
            public R call() {
                T a = resourceFactory.call();
                try {
                    R b = resourceMapper.call(a);
                    ref.set(a);
                    return b;
                } catch (Throwable e) {
                    ResourceManager.this.disposeAction.call(a);
                    if (e instanceof RuntimeException) {
                        throw (RuntimeException) e;
                    } else {
                        throw new RuntimeException(e);
                    }
                }
            }
        };
        Action1<R> disposer = new Action1<R>() {
            @Override
            public void call(R r) {
                disposeAction.call(r);
                ResourceManager.this.disposeAction.call(ref.get());
            }
        };
        return create(rf, disposer);
    }

    public <R extends Closeable> ResourceManager<R> map(
            final Func1<? super T, ? extends R> resourceMapper) {
        return map(resourceMapper, CloserHolder.INSTANCE);
    }

    private static final class CloserHolder {

        static final Action1<Closeable> INSTANCE = new Action1<Closeable>() {

            @Override
            public void call(Closeable c) {
                try {
                    c.close();
                } catch (IOException e) {
                    RxJavaHooks.onError(e);
                }
            }
        };
    }

    public static void main(String[] args) {
        ResourceManager.forOutput(new File("target/test")); //
    }
}
