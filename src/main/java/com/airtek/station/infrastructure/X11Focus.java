package com.airtek.station.infrastructure;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * Localiza la ventana del juego en el display Xvfb y fuerza foco antes de XTEST.
 */
final class X11Focus {

    private static final int REVERT_TO_POINTER_ROOT = 1;

    private X11Focus() {
    }

    static long findGameWindow(Pointer display) {
        if (display == null) {
            return 0;
        }
        X11 lib = X11.INSTANCE;
        long root = lib.XDefaultRootWindow(display);
        long found = search(display, lib, root, 0);
        if (found != 0) {
            return found;
        }
        IntByReference nChildren = new IntByReference();
        PointerByReference childrenRef = new PointerByReference();
        NativeLong rootRet = new NativeLong();
        NativeLong parentRet = new NativeLong();
        if (lib.XQueryTree(display, root, rootRet, parentRet, childrenRef, nChildren) == 0) {
            return 0;
        }
        int n = nChildren.getValue();
        if (n <= 0) {
            return 0;
        }
        Pointer children = childrenRef.getValue();
        if (children == null) {
            return 0;
        }
        int[] kids = children.getIntArray(0, n);
        lib.XFree(children);
        return kids[n - 1] & 0xffffffffL;
    }

    static void raiseAndFocus(Pointer display, long window) {
        if (display == null || window == 0) {
            return;
        }
        X11 lib = X11.INSTANCE;
        lib.XRaiseWindow(display, window);
        lib.XSetInputFocus(display, window, REVERT_TO_POINTER_ROOT, new NativeLong(0));
        lib.XFlush(display);
    }

    private static long search(Pointer display, X11 lib, long window, int depth) {
        if (depth > 10) {
            return 0;
        }
        XClassHint hint = new XClassHint();
        if (lib.XGetClassHint(display, window, hint) != 0) {
            String cls = hint.resClassText().toLowerCase();
            if (cls.contains("xonotic") || cls.contains("supertux") || cls.contains("openmw")) {
                freeHint(lib, hint);
                return window;
            }
            freeHint(lib, hint);
        }
        IntByReference nChildren = new IntByReference();
        PointerByReference childrenRef = new PointerByReference();
        NativeLong rootRet = new NativeLong();
        NativeLong parentRet = new NativeLong();
        if (lib.XQueryTree(display, window, rootRet, parentRet, childrenRef, nChildren) == 0) {
            return 0;
        }
        int n = nChildren.getValue();
        Pointer children = childrenRef.getValue();
        if (children == null || n <= 0) {
            return 0;
        }
        int[] kids = children.getIntArray(0, n);
        lib.XFree(children);
        for (int kid : kids) {
            long child = kid & 0xffffffffL;
            long found = search(display, lib, child, depth + 1);
            if (found != 0) {
                return found;
            }
        }
        return 0;
    }

    private static void freeHint(X11 lib, XClassHint hint) {
        if (hint.res_name != null) {
            lib.XFree(hint.res_name);
        }
        if (hint.res_class != null) {
            lib.XFree(hint.res_class);
        }
    }

    @Structure.FieldOrder({"res_name", "res_class"})
    static class XClassHint extends Structure {
        public Pointer res_name;
        public Pointer res_class;

        String resClassText() {
            return res_class == null ? "" : res_class.getString(0);
        }
    }

    interface X11 extends Library {
        X11 INSTANCE = Native.load("X11", X11.class);

        long XDefaultRootWindow(Pointer display);

        int XQueryTree(
                Pointer display,
                long window,
                NativeLong root_return,
                NativeLong parent_return,
                PointerByReference children_return,
                IntByReference nchildren_return
        );

        int XFree(Pointer data);

        int XGetClassHint(Pointer display, long window, XClassHint class_hints_return);

        int XRaiseWindow(Pointer display, long window);

        int XSetInputFocus(Pointer display, long focus, int revert_to, NativeLong time);

        int XFlush(Pointer display);
    }
}
