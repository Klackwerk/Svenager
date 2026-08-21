# Svenager reference Ansible repository

This directory is the **reference implementation** of the Ansible repository
convention that the Svenager server can analyze. It also ships the base role
every managed device gets (agent prerequisites, localhost-bound VNC server).

Real deployments keep their Ansible configuration in **separate git
repositories** registered in the Svenager UI — this repo documents the layout
they must follow and is fully testable on its own (no Svenager required).

## Convention (analyzed by the server)

```
roles/<name>/meta/main.yml            # galaxy_info.description → role card text
roles/<name>/meta/argument_specs.yml  # typed variables → friendly UI forms
roles/<name>/defaults/main.yml        # default values shown in the UI
svenager.yml                          # optional: display names, which roles
                                      #   are user-assignable vs internal
```

The server only **parses** repository content — it never executes anything
from it on the server side. Devices run roles locally with
`ansible-playbook -c local` against a pinned commit.

## Roles

- **svenager_base** *(base — applied automatically)*: the packages every
  managed device needs to run jobs.
- **vnc_server** *(assignable)*: localhost-bound VNC server for the remote
  view (wayvnc by default, x11vnc for X11 sessions).
- **motd_banner** *(base — applied automatically)*: large `/etc/motd` banner
  telling anyone at the console that the device is enrolled in Svenager,
  including the instance URL.
- **kde_desktop** *(assignable)*: KDE Plasma for Debian 13 workstations with
  Windows-familiar defaults and OnlyOffice as the default office suite.
- **notebook** *(assignable)*: laptop optimizations (TLP, zram).
- **kiosk** *(assignable)*: boots a Raspberry Pi OS Lite / Debian console
  image into a fullscreen web kiosk — sway compositor + cog (WPE WebKit)
  showing `kiosk_url`, with its own localhost-bound wayvnc for remote view
  and control (set `kiosk_compositor: cage` for a watch-only fallback).
  By default it shows a status page stored on the device (survives server
  and network outages, with "Svenager server" and "Internet" reachability
  flags fed by `<instance>/kiosk-demo/<device-id>/status`).

The agent injects `svenager_server_url` and `svenager_device_id` as
extra-vars with every job, so role defaults can reference the managing
instance. The server additionally injects `svenager_hostname` — the device
name maintained in the Svenager UI — which the base role applies as the
system hostname.

## Testing

```bash
ansible-lint
molecule test   # against a Debian container (display-less parts only)
```
