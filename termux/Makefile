CC ?= cc
CFLAGS ?= -O2 -Wall
PREFIX ?= /usr/local

tav: tav.c
	$(CC) $(CFLAGS) -pthread -o $@ $< -lm

install: tav
	install -d $(PREFIX)/bin
	install -m 755 tav $(PREFIX)/bin/tav

clean:
	rm -f tav

.PHONY: install clean
