import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { test, expect, waitUntil } from '@drownek/plugwright';

// Console commands are asynchronous. A unique acknowledgement confirms Paper processed them.
async function consoleCommands(server, player, commands) {
  const marker = `fixture-${randomUUID()}`;
  for (const command of commands) server.execute(command);
  server.execute(`minecraft:tellraw ${player.username} {"text":"${marker}"}`);
  await expect(player).toHaveReceivedMessage(marker);
}

function blockAt(player, x, z) {
  return player.bot.blockAt(player.bot.entity.position.clone().set(x, 64, z))?.name;
}

async function hasBlock(player, x, z, material, signal) {
  await waitUntil(() => blockAt(player, x, z) === material, {
    timeout: 10000, signal,
    message: `${player.username}: expected ${material} at ${x},64,${z}`,
  });
}

async function walkToX(player, x, signal) {
  const direction = Math.sign(x - player.bot.entity.position.x);
  await player.bot.look(-direction * Math.PI / 2, 0, true);
  player.bot.setControlState('forward', true);
  try {
    await waitUntil(() => direction * (player.bot.entity.position.x - x) >= 0, {
      timeout: 5000, interval: 25, signal, message: `Walking to x=${x} failed`,
    });
  } finally {
    player.bot.clearControlStates();
  }
}

async function prepare(server, player, z, signal) {
  await consoleCommands(server, player, [
    'minecraft:gamerule minecraft:random_tick_speed 0',
    'minecraft:time set noon',
    `minecraft:fill -4 64 ${z - 3} 12 64 ${z + 3} grass_block`,
    `minecraft:fill -4 65 ${z - 3} 12 69 ${z + 3} air`,
    `minecraft:fillbiome -4 64 ${z - 3} 12 69 ${z + 3} plains`,
  ]);
  await player.teleport(0.5, 65, z + 0.5);
  await hasBlock(player, 1, z, 'grass_block', signal);
  await waitUntil(() => player.bot.entity.onGround, { signal });
}

async function walkLaps(player, signal) {
  for (let lap = 0; lap < 4; lap++) {
    await walkToX(player, 2.5, signal);
    await walkToX(player, 0.5, signal);
  }
}

async function observeWalkingSpeed(player, predicate, action, signal) {
  const client = player.bot._client;
  return new Promise((resolve, reject) => {
    let timeout;
    let observedSpeed;
    let actionCompleted = false;
    const finish = () => {
      if (observedSpeed === undefined || !actionCompleted) return;
      cleanup();
      resolve(observedSpeed);
    };
    const cleanup = () => {
      client.removeListener('abilities', onAbilities);
      clearTimeout(timeout);
      signal?.removeEventListener('abort', onAbort);
    };
    const onAbort = () => {
      cleanup();
      reject(signal.reason ?? new Error('aborted'));
    };
    const onAbilities = (packet) => {
      if (typeof packet.walkingSpeed === 'number' && predicate(packet.walkingSpeed)) {
        observedSpeed = packet.walkingSpeed;
        finish();
      }
    };
    client.on('abilities', onAbilities);
    timeout = setTimeout(() => {
      cleanup();
      reject(new Error('Paper did not send the expected native walking-speed packet'));
    }, 10000);
    signal?.addEventListener('abort', onAbort, { once: true });
    Promise.resolve().then(action).then(() => {
      actionCompleted = true;
      finish();
    }).catch((error) => {
      cleanup();
      reject(error);
    });
  });
}

test('walking wears grass into a trail on real Paper', async ({ player, server, signal }) => {
  await prepare(server, player, 0, signal);
  await walkLaps(player, signal);
  await hasBlock(player, 1, 0, 'dirt', signal);
});

test('trails off prevents wear; trails on restores it', async ({ player, server, signal }) => {
  await prepare(server, player, 8, signal);
  player.chat('/trails off');
  await expect(player).toHaveReceivedMessage('Your trails are now disabled');
  await walkLaps(player, signal);
  assert.equal(blockAt(player, 1, 8), 'grass_block');
  player.chat('/trails on');
  await expect(player).toHaveReceivedMessage('Your trails are now enabled');
  await walkLaps(player, signal);
  await hasBlock(player, 1, 8, 'dirt', signal);
});

test('native trail speed boost applies on a worn block and restores after leaving it', async ({ player, server, signal }) => {
  await prepare(server, player, 24, signal);
  await walkLaps(player, signal);
  await hasBlock(player, 1, 24, 'dirt', signal);
  await player.teleport(0.5, 65, 24.5);
  const boosted = await observeWalkingSpeed(player, (speed) => speed > 0.1001, () => walkToX(player, 1.7, signal), signal);
  assert.ok(boosted > 0.1, `Expected native trail speed above 0.1, got ${boosted}`);
  const restored = await observeWalkingSpeed(player, (speed) => speed <= 0.10001, () => walkToX(player, 3.5, signal), signal);
  assert.ok(Math.abs(restored - 0.1) <= 0.00001, `Expected native speed to restore to 0.1, got ${restored}`);
});

test('native idle decay regresses a worn trail after the fixture idle window', async ({ player, server, signal }) => {
  await prepare(server, player, 32, signal);
  await walkLaps(player, signal);
  await hasBlock(player, 1, 32, 'dirt', signal);
  await player.teleport(20.5, 65, 32.5);
  await waitUntil(() => blockAt(player, 1, 32) === 'grass_block', {
    signal,
    timeout: 90000,
    interval: 500,
    message: 'Native idle trail decay did not restore the worn block',
  });
  assert.equal(blockAt(player, 1, 32), 'grass_block');
});

test('road preview is private; commit and undo update both clients', async ({ player, server, createPlayer, signal }) => {
  await prepare(server, player, 16, signal);
  await player.makeOp();
  player.chat('/trails off');
  await expect(player).toHaveReceivedMessage('Your trails are now disabled');
  const observer = await createPlayer({ username: 'RoadObserver' });
  await observer.teleport(4.5, 65, 18.5);
  await hasBlock(observer, 2, 16, 'grass_block', signal);

  player.chat('/trails build start footpath');
  await expect(player).toHaveReceivedMessage('Road preview footpath is enabled');
  await walkToX(player, 6.5, signal);
  await hasBlock(player, 2, 16, 'yellow_concrete', signal);
  assert.equal(blockAt(observer, 2, 16), 'grass_block');
  // Read authoritative server state as well as the two clients' packet-backed views.
  const marker = `world-unchanged-${randomUUID()}`;
  server.execute(`minecraft:execute if block 2 64 16 grass_block run tellraw ${observer.username} {"text":"${marker}"}`);
  await expect(observer).toHaveReceivedMessage(marker);

  player.chat('/trails build commit');
  await expect(player).toHaveReceivedMessage(/Committed \d+ road blocks/);
  await hasBlock(player, 2, 16, 'dirt_path', signal);
  await hasBlock(observer, 2, 16, 'dirt_path', signal);
  player.chat('/trails build undo');
  await expect(player).toHaveReceivedMessage(/Restored \d+ blocks from the last road commit/);
  await hasBlock(player, 2, 16, 'grass_block', signal);
  await hasBlock(observer, 2, 16, 'grass_block', signal);
});
