#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <stdint.h>
#include <sys/mman.h>
#include <unistd.h>

struct Look {
    int lock;
    int dx;
    int dy;
    int buttons;
};

static struct Look *look;
static int look_ready;
static uint32_t (*real_relative)(int *, int *);

static void open_look(void) {
    int fd;
    if (look_ready) {
        return;
    }
    fd = open("/tmp/game-input", O_RDWR);
    if (fd < 0) {
        return;
    }
    look = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (look == MAP_FAILED) {
        look = NULL;
        return;
    }
    look_ready = 1;
}

static void take(int *dx, int *dy) {
    open_look();
    if (!look) {
        *dx = 0;
        *dy = 0;
        return;
    }
    *dx = __atomic_exchange_n(&look->dx, 0, __ATOMIC_ACQ_REL);
    *dy = __atomic_exchange_n(&look->dy, 0, __ATOMIC_ACQ_REL);
}

uint32_t SDL_GetRelativeMouseState(int *x, int *y) {
    int dx = 0;
    int dy = 0;
    uint32_t buttons = 0;
    if (!real_relative) {
        real_relative = dlsym(RTLD_NEXT, "SDL_GetRelativeMouseState");
    }
    if (real_relative) {
        buttons = real_relative(x, y);
    }
    take(&dx, &dy);
    if (x) {
        *x = dx;
    }
    if (y) {
        *y = dy;
    }
    return buttons;
}
