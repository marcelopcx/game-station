#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#define SDL_EVENT_SIZE 56
#define SDL_WINDOWEVENT 0x200u
#define SDL_WINDOWEVENT_FOCUS_LOST 13
#define SDL_WINDOWEVENT_FOCUS_GAINED 12

struct Look {
    int lock;
    int dx;
    int dy;
    int buttons;
};

static struct Look *look;
static int look_ready;
static uint32_t (*real_relative)(int *, int *);
static int (*real_poll)(void *);

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

static uint32_t relative_state(int *x, int *y) {
    int dx = 0;
    int dy = 0;
    int rx = 0;
    int ry = 0;
    uint32_t buttons = 0;
    if (!real_relative) {
        real_relative = dlsym(RTLD_NEXT, "SDL_GetRelativeMouseState");
    }
    if (real_relative) {
        buttons = real_relative(&rx, &ry);
    }
    take(&dx, &dy);
    if (x) {
        *x = rx + dx;
    }
    if (y) {
        *y = ry + dy;
    }
    return buttons;
}

uint32_t SDL_GetRelativeMouseState(int *x, int *y) {
    return relative_state(x, y);
}

static void keep_focus(void *event) {
    uint32_t type = 0;
    uint8_t code = 0;
    memcpy(&type, event, sizeof(type));
    if (type != SDL_WINDOWEVENT) {
        return;
    }
    memcpy(&code, (unsigned char *)event + 12, 1);
    if (code == SDL_WINDOWEVENT_FOCUS_LOST) {
        code = SDL_WINDOWEVENT_FOCUS_GAINED;
        memcpy((unsigned char *)event + 12, &code, 1);
    }
}

int SDL_PollEvent(void *event) {
    int pending;
    if (!real_poll) {
        real_poll = dlsym(RTLD_NEXT, "SDL_PollEvent");
    }
    if (!event || !real_poll) {
        return 0;
    }
    pending = real_poll(event);
    if (pending) {
        keep_focus(event);
    }
    return pending;
}
