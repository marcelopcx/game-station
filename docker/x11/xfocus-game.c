#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int class_matches(XClassHint *hint) {
    if (!hint->res_class) {
        return 0;
    }
    return strcasestr(hint->res_class, "xonotic") != NULL;
}

static Window find_game(Display *dpy, Window root, int depth) {
    Window root_ret = 0;
    Window parent = 0;
    Window *kids = NULL;
    unsigned int n = 0;
    Window found = 0;
    XClassHint hint;

    if (depth > 10) {
        return 0;
    }
    if (XGetClassHint(dpy, root, &hint)) {
        if (class_matches(&hint)) {
            XFree(hint.res_name);
            XFree(hint.res_class);
            return root;
        }
        if (hint.res_name) {
            XFree(hint.res_name);
        }
        if (hint.res_class) {
            XFree(hint.res_class);
        }
    }
    if (!XQueryTree(dpy, root, &root_ret, &parent, &kids, &n)) {
        return 0;
    }
    for (unsigned int i = 0; i < n; i++) {
        found = find_game(dpy, kids[i], depth + 1);
        if (found) {
            break;
        }
    }
    if (kids) {
        XFree(kids);
    }
    return found;
}

static int focus_game(Display *dpy) {
    Window root = DefaultRootWindow(dpy);
    Window target = find_game(dpy, root, 0);
    if (!target) {
        Window root_ret = 0;
        Window parent = 0;
        Window *kids = NULL;
        unsigned int n = 0;
        if (!XQueryTree(dpy, root, &root_ret, &parent, &kids, &n) || n == 0) {
            if (kids) {
                XFree(kids);
            }
            return 1;
        }
        target = kids[n - 1];
        XFree(kids);
    }
    XRaiseWindow(dpy, target);
    XSetInputFocus(dpy, target, RevertToPointerRoot, CurrentTime);
    XFlush(dpy);
    return 0;
}

int main(int argc, char **argv) {
    const char *display_name = argc > 1 ? argv[1] : NULL;
    unsigned interval_ms = 400;
    if (argc > 2) {
        int parsed = atoi(argv[2]);
        if (parsed > 50 && parsed < 60000) {
            interval_ms = (unsigned)parsed;
        }
    }
    Display *dpy = XOpenDisplay(display_name);
    if (!dpy) {
        return 1;
    }
    for (;;) {
        focus_game(dpy);
        usleep(interval_ms * 1000U);
    }
}
