#include <stdio.h>

#include "aead.h"

int main(void) {
    if (aead_selftest() != 0) {
        fprintf(stderr, "aead_selftest failed\n");
        return 1;
    }
    return 0;
}
