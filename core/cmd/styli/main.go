// Command styli is the single Styli binary (hub server now, more modes later).
package main

import "fmt"

// Version is the build version; the Makefile can override it with -ldflags.
var Version = "dev"

func main() {
	fmt.Println("styli", Version)
}
