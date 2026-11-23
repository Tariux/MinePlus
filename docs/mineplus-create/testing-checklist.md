# Mineplus Create — In-Game Testing Checklist

Everything below is testable on a Paper 1.21+ server with **Mineplus** (Core) and
**MineplusCreate** installed. Give yourself `minepluscreate.admin` for the
`/mcadmin` helper commands.

## 0. Boot check

- [ ] Server starts with no `FATAL: Mineplus Core plugin was not found.`
- [ ] Console shows no config errors from `multiblocks/mc_*.json` or
      `recipes/mc_machine_recipes.json` (these are installed into the Core data
      folder on first enable — check they exist after first boot)
- [ ] `models/mc-*.bbmodel` + `.meta.json` are in the Core data folder

## 1. Quick staging with `/mcadmin` (admin)

- [ ] `/mcadmin place mc_hand_crank` stages a hand crank at your feet
- [ ] `/mcadmin place mc_millstone` stages a millstone
- [ ] `/mcadmin status` lists every projected network node with type, RPM,
      stress and network id (after section 2 you should see nodes here)

## 2. T0–T1 rotational machines

- [ ] Right-click a placed **hand crank**: message "the shaft starts turning (3s)",
      the network spins up and spins back down after 3 s (`/mcadmin status`)
- [ ] Place a **water wheel** as a standing source: its shaft holds constant RPM
- [ ] Attach a **millstone** through shafts/cogwheels; the overload rule still
      snaps the weakest shaft when stress exceeds capacity (regression-tested in
      `RotationalNetworkTest`, but worth seeing in game)

## 3. Machine crafting (millstone / press)

- [ ] Hold **wheat**, right-click the millstone: "Working: mc_wheat_flour";
      the wheat leaves your hand
- [ ] When the process completes you receive **"Wheat Flour"** (named Sugar
      carrier). It stacks with other flour
- [ ] Bone → Bone Meal, Sugar Cane → Sugar on the millstone
- [ ] Hold **iron ingot** on the mechanical press → **"Iron Sheet"** (named
      Iron Ingot); gold ingot → **"Gold Sheet"**
- [ ] Wrong item in hand: the machine lists valid recipe inputs
- [ ] Starting while busy: "The machine is already busy."

## 4. T2 steam

- [ ] Right-click a placed **steam engine** with nothing: "Boiler is cold."
- [ ] Right-click with coal: boiler refuels, engine joins the network as a
      48 RPM source (`/mcadmin status` shows rpm 48)
- [ ] Wait for burn-out (1 coal = 80 s): the engine stops driving the network
- [ ] Charcoal refuels too

## 5. T3 electric

- [ ] Place a **dynamo** on a spinning network, right-click it: buffer reading
      with FE/t at the current RPM (0.5 FE/t per |RPM|)
- [ ] On a stopped network it reports 0 FE/t

## 6. Inventor's Journal (unchanged, re-test)

- [ ] `/journal` shows the T0–T3 ladder; `/journal research t1` spends 3 levels,
      T2 refuses without T1

## 7. Towns and contracts (Phase 3)

- [ ] `/town create <name>` founds a town; duplicate name or second town refused
- [ ] Second player: `/town join <name>`; `/town info` shows tier, contracts
      done, member count and the co-op stress bonus
- [ ] `/contracts` lists Flour Delivery (16 units, tier 1), Sheet Metal Order
      (tier 2) and The Great Press Run (tier 3, greyed out)
- [ ] Mill/press output items count toward shared progress; `/contracts complete
      flour_delivery` before enough progress is refused
- [ ] Completing pays XP levels to every online member and increments the town
      tier (`/town info`)

## 8. Ores (Phase 4)

- [ ] Fly to wilderness chunks (beyond 8 chunks from spawn): zinc Y5–40,
      bauxite Y24–56, aluminum Y48–72, ~2 veins/chunk
- [ ] No ores within the spawn radius

## Known session-only state (by design)

- Steam burn levels and dynamo FE buffers reset on server restart
- Town membership and journal research persist (PDC)
- Leaving a town is admin-only for now (`/town leave` says so)
