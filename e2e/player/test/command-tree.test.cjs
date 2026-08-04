const assert = require('node:assert/strict')
const test = require('node:test')
const { literalChildren, literalChildrenAt } = require('./command-tree.cjs')

test('returns sorted literal children for one command root', () => {
  const packet = {
    rootIndex: 0,
    nodes: [
      { flags: { command_node_type: 0 }, children: [1, 4] },
      { flags: { command_node_type: 1 }, children: [3, 2, 5], extraNodeData: { name: 'wm' } },
      { flags: { command_node_type: 1 }, children: [], extraNodeData: { name: 'warp' } },
      { flags: { command_node_type: 1 }, children: [], extraNodeData: { name: 'list' } },
      { flags: { command_node_type: 1 }, children: [], extraNodeData: { name: 'help' } },
      { flags: { command_node_type: 2 }, children: [], extraNodeData: { name: '__wm_invalid' } }
    ]
  }

  assert.deepEqual(literalChildren(packet, 'wm'), ['list', 'warp'])
  assert.deepEqual(literalChildren(packet, 'missing'), undefined)
})

test('accepts a visible command root without executable children', () => {
  const packet = {
    nodes: [
      { flags: { command_node_type: 0 }, children: [1] },
      { flags: { command_node_type: 1 }, children: [], extraNodeData: { name: 'wm' } }
    ]
  }

  assert.deepEqual(literalChildren(packet, 'wm'), [])
})

test('returns children below a nested literal path', () => {
  const packet = {
    nodes: [
      { flags: 0, children: [1] },
      { flags: 1, children: [2], extraNodeData: { name: 'wm' } },
      { flags: 1, children: [4, 3], extraNodeData: { name: 'tp' } },
      { flags: 1, children: [], extraNodeData: { name: 'self' } },
      { flags: 1, children: [], extraNodeData: { name: '--any' } }
    ]
  }

  assert.deepEqual(literalChildrenAt(packet, ['wm', 'tp']), ['--any', 'self'])
  assert.deepEqual(literalChildrenAt(packet, ['wm', 'missing']), undefined)
})

test('rejects malformed command node references', () => {
  assert.throws(() => literalChildren({ nodes: [{ flags: 0, children: [2] }] }, 'wm'), /invalid node 2/)
})