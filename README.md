# styli - The final notes app?

Other notes apps worked well for me, but I realized that I ended up using a bunch of external tools to do things that I wish were just baked into my note taking app.

I needed a markdown based knowledge base like obsidian with a native handwriting first approach, able to be hosted on my own devices, synced with one another without any accounts being made. I wanted Github like syncing across my different devices sharing notes.
I wanted an AI to help organize my notes via topic, instead of having to make the links myself, and without the security concern of an AI model in the cloud rummaging through all my notes.

I couldn't find the right tool, and didnt want to jerry-rig a bunch of systems together, thats why I am building Styli.

## Core Values

### Fully Local - nothing happens off your devices (other than an optional time check)

All services run on devices you own under your control, your notes are stored as plain markdown or pdf, the AI that creates the markdown copy of your handwritten notes runs locally on your machine, with you being able to choose which model to use as your reader, 
the AI that scans your notes to group them together on your mind map is the same. All with the option of not having the feature on your device at all.

### Complete User Control

Any service (within reason) can be turned off or altered to fit the users needs. Don't want a reader? turn it off, don't want version history? turn it off, I can't decide what is best for everyone, the defaults are mostly based off what I like, so I may as well let
the user do the hard part of fine tuning all the settings to meet what they need.

### Anybody Can Use It

It's free, and easy to use, It is easy to setup the AI features, the hub, and the nodes, a complicated setup would make it so I probably couldn't use it either.

## Features

### Native Handwriting Support

Styli is being built with a handwriting-first approach on Android devices.

Drawing tablet support will come later, along with support for iOS devices.

### The Hub (name is a work in progress) 

The core node of the system, most likely the only node that holds every version of every file, the nodes report back to the hub, and the hub sends the required updates to the other nodes if needed

The hub also hosts a dashboard to do admin level tasks, if the maid comes back and reports that it was requested to delete an abormal amount of items, it reports it to the hub, and the hub asks you to double check before deleting, other notifications here could include
merge conflicts, zombie files (files that come back after being deleted due to edits taking place on another device after) etc...

The hub also has a rebuilding protocol, let's say the hub dies, the PC running it exploded maybe, you can appoint a new hub, and ask all nodes to merge their locally stored files onto it (with no dupes of course), hopefully you had copies of everything but if not that's not on me.

### The maid

A programmable script that cleans your trash and version history based on parameters you set, its basically just like 4 settings

### The Graph

Very similar to obsidian graph with their link system or whatever it is, but now this graph is automated, with the linked being generated automatically by the reader, also I may not present this in a graph like layout I have some ideas flowing

### The Reader

A configurable, locally hosted AI that reads your PDF's you hand write and converts them to markdown, using mermaid code (who the hell named that by the way) for the graphs, implementing HTML that will be hidden in the markdown previews 
to indicate page breaks, and layout changes

The reader also handles links, grouping notes together if they are the same topic, although you are able to mark notes as do not group by pinning.

### Other features

- Version history
- GitHub-style merging
- Hub rebuilding protocol
- Pinning
- Trash and recovery

## What can I run it on?

The current plan for the initial release is that the hub can be run on Windows, Linux, and FreeBSD, MacOS will come in the future.

Hopefully I can get native android support easily, for now iPhones will have to access through a web app cause I don't have apple dev account :(
Mac users can still reach the web app as well obviously, just cannot be the hub.

I do not plan on adding mobile hub support, but I will add standalone mobile support (no hub, don't know about adding AI to that yet)

## Development Status

Phase 1 (the Go core on one machine) is complete! There is a hub server with a JSON API, and the web interface is being built screen by screen (the tabularium view with search and notes, and the editor with autosave, move and trash, and the Trash screen, version history, the hub dashboard and the Settings screen are in, which completes the screens planned for phase 1).

The design is in `docs/DESIGN.md`.

## Screenshot of it now

It right now looks like garbage but I'll get on that

<img width="2558" height="1437" alt="image" src="https://github.com/user-attachments/assets/50856137-d03b-4a3a-ad07-e706d875dd0f" />

## Run the hub

```
make build
./out/styli serve --vault ~/notes
```

- `--vault` is the notes folder. It must already exist.
- The hub listens on `http://127.0.0.1:7878` and **refuses any other address**, because there is no login yet. Nothing on your network can reach it.
- `--data` (search index) and `--config` (this device's settings) default to your cache and config folders.
- Stop it with Ctrl-C. It shuts down cleanly.

## Develop

```
make test    # Go tests, plus the UI helper tests if Node (22.7 or newer) is installed
make cross   # check it builds for linux, freebsd and windows
make ui-assets  # copy the design system (design/) into the web UI; only when the design changes
```
