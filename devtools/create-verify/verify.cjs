const { spawn } = require('node:child_process')
const { once } = require('node:events')
const path = require('node:path')
const assert = require('node:assert/strict')
const mineflayer = require('mineflayer')
const { Vec3 } = require('vec3')
const server = spawn('java', ['-Xms512M', '-Xmx2G', '-jar', 'paper.jar', '--nogui', '--host', '127.0.0.1'], {
  cwd: path.resolve(__dirname, '../../paper-test'), stdio: ['pipe', 'pipe', 'pipe']
})
let output = ''
server.stdout.on('data', chunk => { output += chunk; process.stdout.write(chunk) })
server.stderr.on('data', chunk => process.stderr.write(chunk))
const failures = []
let bot
const messages = []
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
async function until(predicate, label, timeout = 10000) {
  const end = Date.now() + timeout
  while (Date.now() < end) {
    if (predicate()) return
    await delay(100)
  }
  throw new Error('Timed out: ' + label)
}
function consoleCommand(text) { server.stdin.write(text + '\n') }
async function chat(text, expected) {
  const start = messages.length
  bot.chat(text)
  await until(() => messages.slice(start).some(line => line.includes(expected)), text + ' -> ' + expected)
  await delay(300)
  return messages.slice(start).join('\n')
}
async function test(name, run) {
  try { await run(); console.log('PLAYER PASS: ' + name) }
  catch (error) { failures.push(name + ': ' + error.message); console.error('PLAYER FAIL: ' + name + ': ' + error.message) }
}
async function teleport(x, z = 0) {
  consoleCommand(`tp ${bot.username} ${x + 0.5} 81 ${z + 0.5}`)
  await until(() => Math.abs(bot.entity.position.x - x - 0.5) < .2 && Math.abs(bot.entity.position.z - z - .5) < .2, 'teleport')
  await bot.waitForTicks(10)
}
async function equip(name, count = 1) {
  consoleCommand(`give ${bot.username} minecraft:${name} ${count}`)
  await until(() => bot.inventory.items().some(item => item.name === name), 'receive ' + name)
  await bot.equip(bot.inventory.items().find(item => item.name === name), 'hand')
}
async function click(x, expected) {
  await teleport(x, 2)
  const start = messages.length
  const target = new Vec3(x + .5, 81.5, .5)
  const entity = Object.values(bot.entities).find(e => e.name === 'interaction' && e.position.distanceTo(target) < 1.5)
  if (entity) {
    await bot.lookAt(entity.position.offset(0, .4, 0))
    bot.activateEntity(entity)
  } else {
    const block = bot.blockAt(new Vec3(x, 81, 0))
    assert(block && block.name !== 'air', 'No rendered interaction entity or machine block at ' + x)
    await bot.activateBlock(block)
  }
  await until(() => messages.slice(start).some(line => line.includes(expected)), 'machine click -> ' + expected, 5000)
  return messages.slice(start).join('\n')
}
async function main() {
  await until(() => output.includes('Done ('), 'Paper startup', 90000)
  bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: 'Verify' + Date.now().toString().slice(-7), version: '1.21.1', auth: 'offline' })
  bot.on('messagestr', text => { messages.push(text); console.log('PLAYER CHAT: ' + text) })
  bot.on('error', error => console.error('CLIENT ERROR: ' + error.message))
  bot.on('kicked', reason => console.error('CLIENT KICKED: ' + JSON.stringify(reason)))
  await Promise.race([once(bot, 'spawn'), delay(30000).then(() => { throw new Error('Client spawn timed out') })])
  consoleCommand('op ' + bot.username)
  consoleCommand('gamemode creative ' + bot.username)
  consoleCommand('gamerule doMobSpawning false')
  const base = Math.floor(Date.now() / 1000) % 10000
  consoleCommand(`forceload add ${base - 3} -3 ${base + 30} 4`)
  await delay(1000)
  consoleCommand(`fill ${base - 3} 80 -3 ${base + 30} 80 4 stone`)
  await delay(500)
  await test('player journal and XP research', async () => {
    await chat('/journal', "Inventor's Journal")
    await chat('/journal research t3_electric', 'Cannot research')
    consoleCommand(`experience set ${bot.username} 50 levels`)
    for (const [id, title] of [['t0_workshop_basics', 'Workshop Basics'], ['t1_rotation', 'Rotational Power'], ['t2_steam', 'Steam & Brass'], ['t3_electric', 'Electric Age']]) {
      await chat('/journal research ' + id, 'Researched: ' + title)
    }
    await until(() => bot.experience.level === 24, 'research XP cost')
  })
  await test('town creation and contract gating', async () => {
    await chat('/contracts', 'Join a town first')
    await chat('/town create ' + bot.username, 'Founded')
    await chat('/town info', 'tier 1')
    await chat('/contracts', 'flour_delivery')
    await chat('/contracts complete flour_delivery', 'Not enough shared progress')
  })
  const machines = [['mc_hand_crank', 0], ['mc_millstone', 1], ['mc_mechanical_press', 5], ['mc_water_wheel', 6], ['mc_steam_engine', 10], ['mc_dynamo', 11], ['mc_windmill', 16]]
  for (const [type, offset] of machines) {
    await test('place ' + type, async () => {
      await teleport(base + offset)
      await chat('/mcadmin place ' + type, 'Staged ' + type)
      const status = await chat('/mcadmin status', 'Rotational networks')
      if (type !== 'mc_steam_engine') assert(status.includes(`world;${base + offset};81;0`), 'Staged machine missing from rotational network')
    })
  }
  await test('hand crank starts network', async () => {
    await click(base, 'You crank the handle')
    await bot.waitForTicks(20)
    const status = await chat('/mcadmin status', 'Rotational networks')
    assert.match(status, new RegExp(`world;${base};81;0.*rpm=[1-9]`))
  })
  await test('millstone payout and contract progress', async () => {
    await equip('wheat', 2)
    await click(base + 1, 'Working: mc_wheat_flour')
    await until(() => bot.inventory.items().some(item => item.name === 'sugar'), 'flour payout', 10000)
    const board = await chat('/contracts', 'flour_delivery')
    assert(board.includes('1/16'), 'Crafted flour did not advance shared contract')
  })
  await test('press input consumption and payout', async () => {
    await equip('iron_ingot', 2)
    await click(base + 5, 'Working: mc_iron_sheet')
    await until(() => bot.inventory.items().some(item => item.name === 'iron_ingot' && JSON.stringify(item).includes('Iron Sheet')), 'sheet payout', 10000)
    assert.equal(bot.inventory.items().filter(i => i.name === 'iron_ingot' && !JSON.stringify(i).includes('Iron Sheet')).reduce((n, i) => n + i.count, 0), 1)
  })
  await test('steam coal consumption and dynamo generation', async () => {
    await equip('coal', 2)
    await click(base + 10, 'Boiler refuelled')
    await bot.waitForTicks(60)
    assert.equal(bot.inventory.items().filter(i => i.name === 'coal').reduce((n, i) => n + i.count, 0), 1)
    const message = await click(base + 11, 'Dynamo buffer:')
    assert.match(message, /Dynamo buffer: [1-9][0-9]*\//)
    assert.match(message, /generating [1-9][0-9]* FE\/t/)
  })
  await test('crank burst decays after release', async () => {
    const status = await chat('/mcadmin status', 'Rotational networks')
    assert.match(status, new RegExp(`world;${base};81;0.*rpm=0\\.0`))
  })
  await test('full flour contract pays reward once', async () => {
    await equip('wheat', 15)
    for (let total = 2; total <= 16; total++) {
      await click(base + 1, 'Working: mc_wheat_flour')
      await until(() => bot.inventory.items().filter(i => i.name === 'sugar').reduce((n, i) => n + i.count, 0) === total, 'flour payout ' + total, 10000)
    }
    assert.equal(bot.inventory.items().filter(i => i.name === 'wheat').reduce((n, i) => n + i.count, 0), 1)
    const board = await chat('/contracts', 'flour_delivery')
    assert(board.includes('16/16'))
    const levels = bot.experience.level
    await chat('/contracts complete flour_delivery', 'Contract complete: Flour Delivery')
    await until(() => bot.experience.level === levels + 2, 'contract XP reward')
    await chat('/contracts complete flour_delivery', 'Not enough shared progress')
    assert.equal(bot.experience.level, levels + 2)
  })
  await test('steam burnout stops electric generation', async () => {
    await bot.waitForTicks(400)
    await click(base + 10, 'Boiler is cold')
    const message = await click(base + 11, 'Dynamo buffer:')
    assert.match(message, /generating 0 FE\/t at 0\.0 RPM/)
  })
  if (failures.length) throw new Error(failures.join('\n'))
}
main().catch(error => { console.error(error.stack); process.exitCode = 1 }).finally(async () => {
  if (bot) bot.quit()
  if (server.exitCode === null) {
    consoleCommand('stop')
    const killer = setTimeout(() => server.kill('SIGTERM'), 60000)
    const [code] = await once(server, 'exit')
    clearTimeout(killer)
    console.log('SERVER EXIT: ' + code)
    if (code !== 0) process.exitCode = 1
  }
  console.log('PLAYER VERIFICATION FAILURES: ' + failures.length)
})
