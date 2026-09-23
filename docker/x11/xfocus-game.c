#include <X11/Xlib.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

static int focus_latest(Display *dpy) {
    Window root = DefaultRootWindow(dpy);
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
    Window target = kids[n - 1];
    XRaiseWindow(dpy, target);
    XSetInputFocus(dpy, target, RevertToPointerRoot, CurrentTime);
    XFlush(dpy);
    XFree(kids);
    return 0;
}

int main(int argc, char **argv) {
    const char *display_name = argc > 1 ? argv[1] : NULL;
    unsigned interval_ms = 500;
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
        if (focus_latest(dpy) == 0) {
            /* ok */
        }
        usleep(interval_ms * 1000U);
    }
}
