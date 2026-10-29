# Weapon sounds — local only

The `.ogg` samples in this folder are **not tracked in git** (see the module
`.gitignore`). `sounds.json` (one level up) **is** tracked: it declares the
additive `mineplusgun:` events the runtime plays.

The module installs every `.ogg` here as
`assets/mineplusgun/sounds/<name>.ogg` and mirrors `sounds.json` to
`assets/mineplusgun/sounds.json`. The events it needs:

```
pistol_shot        gun_bow
shotgun_shot       gun_bow, crossbow_shoot4
rifle_shot         crossbow_shoot1..3
battle_rifle_shot  crossbow_shoot1..4
reload_begin       crossbow_load_start, reload_start
reload_mid         crossbow_load_mid1..4
reload_done        crossbow_load_end, reload_end
shot / heavy       shot1..4, heavy1..3
empty              empty
```

Without the samples the module still fires and plays events, but the client has
nothing to play (silent, never an error), and `Sounds` falls back to the vanilla
event named alongside the custom one.
