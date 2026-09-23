#include <X11/Xlib.h>
#include <stdio.h>
#include <stdlib.h>

int main(int argc, char **argv) {
    Display *dpy = XOpenDisplay(argc > 1 ? argv[1] : NULL);
    if (!dpy) {
        return 1;
    }
    Window root = DefaultRootWindow(dpy);
    Window root_ret = 0;
    Window parent = 0;
    Window *kids = NULL;
    unsigned int n = 0;
    if (!XQueryTree(dpy, root, &root_ret, &parent, &kids, &n) || n == 0) {
        if (kids) {
            XFree(kids);
        }
        XCloseDisplay(dpy);
        return 2;
    }
    Window target = kids[n - 1];
    XRaiseWindow(dpy, target);
    XSetInputFocus(dpy, target, RevertToPointerRoot, CurrentTime);
    XFlush(dpy);
    fprintf(stdout, "focus %lu\n", (unsigned long)target);
    XFree(kids);
    XCloseDisplay(dpy);
    return 0;
}
