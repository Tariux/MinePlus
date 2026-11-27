// Bot half of devtools/live-test/run.sh. Two phases, run against a live server:
//   phase1  give -> place -> barriers -> wrong tool refused -> axe break -> record gone
//   phase2  after a restart: the placed block is restored with its barriers
// Exits non-zero on the first failed assertion.
import mineflayer from 'mineflayer'
import { readFileSync } from 'node:fs'

const phase = process.argv[2]
const port = Number(process.env.PORT || 25565)
const version = process.env.MC || '1.21.1'
const stateFile = process.env.STATE || 'plugins/Mineplus/blocks_state.json'

const bot = mineflayer.createBot({ host: '127.0.0.1', port, username: 'Tester', version, auth: 'offline' })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const said = []
let seq = 1

const fail = (message) => {
  for (const line of said.slice(-8)) console.error('  server said: ' + line)
  console.error('FAIL ' + message)
  try { bot.quit() } catch { /* already gone */ }
  process.exit(1)
}
const ok = (message) => console.log('ok   ' + message)

function records() {
  try {
    return JSON.parse(readFileSync(stateFile, 'utf8'))
  } catch {
    return []
  }
}

function scan(name, radius = 6) {
  const p = bot.entity.position.floored()
  const found = []
  for (let x = -radius; x <= radius; x++)
    for (let y = -radius; y <= radius; y++)
      for (let z = -radius; z <= radius; z++) {
        const b = bot.blockAt(p.offset(x, y, z))
        if (b && b.name === name) found.push({ x: p.x + x, y: p.y + y, z: p.z + z })
      }
  return found
}

// Barriers are unbreakable client-side, so Mineflayer's dig will not touch them;
// the server tracks progress from the raw packets.
async function digWith(target, clicks) {
  for (let i = 0; i < clicks; i++) {
    bot._client.write('block_dig', { status: 0, location: target, face: 1, sequence: seq++ })
    await sleep(100)
  }
  bot._client.write('block_dig', { status: 2, location: target, face: 1, sequence: seq++ })
}

bot.on('error', (e) => fail('client error: ' + e.message))
bot.on('message', (m) => said.push(m.toString()))

bot.once('spawn', async () => {
  try {
    await sleep(1500)

    if (phase === 'phase2') {
      const barriers = scan('barrier')
      if (barriers.length === 0) fail('no barrier cells after restart; the placed block was not restored')
      ok(`restored ${barriers.length} barrier cell(s) after restart`)
      if (records().length !== 2) fail(`expected 2 saved records after restart, found ${records().length}`)
      ok('both saved records survived the restart')
    } else {
      bot.chat('/mineplus blocks give mineplus:shaft 1')
      bot.chat('/give Tester wooden_axe 1')
      const hasBlockItem = async () => {
        for (let i = 0; i < 12; i++) {
          if (bot.inventory.items().some((i2) => i2.name === 'paper')) return true
          await sleep(500)
        }
        return false
      }
      if (!(await hasBlockItem())) fail('give did not deliver a block item')
      ok('give delivered the block item')

      const ground = bot.blockAt(bot.entity.position.offset(0, -1, 0))
      await bot.lookAt(ground.position.offset(0.5, 0.9, 0.5), true)
      await sleep(400)
      bot.chat('/mineplus blocks place mineplus:shaft')
      await sleep(2500)

      const barriers = scan('barrier')
      if (barriers.length === 0) fail('no barrier cells after place; the model did not spawn')
      ok(`place spawned ${barriers.length} barrier cell(s)`)
      if (records().length !== 1) fail(`expected 1 saved record after place, found ${records().length}`)
      ok('place saved the record')

      await digWith(barriers[0], 10)
      await sleep(2000)
      if (!said.some((line) => /axe/i.test(line))) fail('bare-hand dig was not refused with a tool hint')
      ok('bare hand refused with a tool hint')
      if (scan('barrier').length !== barriers.length) fail('bare-hand dig changed barriers')
      ok('bare hand left the block intact')

      const axe = bot.inventory.items().find((i) => i.name.endsWith('_axe'))
      if (axe) await bot.equip(axe, 'hand')
      await digWith(barriers[0], 25)
      await sleep(2500)
      if (scan('barrier').length !== 0) fail('axe break left barrier cells behind')
      ok('axe break removed every barrier cell')
      if (records().length !== 0) fail(`break left ${records().length} saved record(s)`)
      ok('break removed the saved record')
      if (!bot.inventory.items().some((i) => i.name === 'paper')) fail('break did not return the block item')
      ok('break returned the block item')

      // leave one placed block behind so phase 2 can prove restore after restart
      await bot.lookAt(ground.position.offset(0.5, 0.9, 0.5), true)
      await sleep(400)
      bot.chat('/mineplus blocks place mineplus:shaft')
      await sleep(2500)
      if (scan('barrier').length === 0) fail('re-placement before restart produced no barriers')
      if (records().length !== 1) fail(`expected 1 saved record before restart, found ${records().length}`)
      ok('left one placed block for the restart check')

      // The wheel's geometry dips one cell below its anchor, so a level ground
      // placement is refused on purpose; place it against a side face one cell
      // up, where its whole footprint is air.
      bot.chat('/setblock ~2 ~1 ~ stone')
      await sleep(600)
      const stone = bot.blockAt(bot.entity.position.floored().offset(2, 1, 0))
      if (!stone || stone.name !== 'stone') fail('could not build the side-face target')
      await bot.lookAt(stone.position.offset(-0.5, 0.5, 0.5), true)
      await sleep(400)
      bot.chat('/mineplus blocks give mineplus:water_wheel_weak 1')
      await sleep(1500)
      bot.chat('/mineplus blocks place mineplus:water_wheel_weak')
      await sleep(2500)
      const wheelBarriers = scan('barrier')
      if (wheelBarriers.length === 0) fail('air placement of the wheel produced no barriers')
      ok(`wheel placed in air with ${wheelBarriers.length} barrier cell(s)`)
      if (records().length !== 2) fail(`expected 2 saved records, found ${records().length}`)
      ok('wheel record saved alongside the shaft')
    }
  } catch (error) {
    fail(error.message)
  }
  bot.quit()
  process.exit(0)
})

setTimeout(() => fail('timed out'), 90000)
