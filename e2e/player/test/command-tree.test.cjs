const assert = require('node:assert/strict')
const test = require('node:test')
const { literalChildren, literalChildrenAt } = require('./command-tree.cjs')

test('returns sorted literal children for one command root', () => {
  const packet = {
    rootIndex: 0,
    nodes: [
      { children: [1, 4] },
      { children: [3, 2], extraNodeData: { name: 'wm' } },
      { children: [], extraNodeData: { name: 'warp' } },
      { children: [], extraNodeData: { name: 'list' } },
      { children: [], extraNodeData: { name: 'help' } }
    ]
  }

  assert.deepEqual(literalChildren(packet, 'wm'), ['list', 'warp'])
  assert.deepEqual(literalChildren(packet, 'missing'), undefined)
})

test('accepts a visible command root without executable children', () => {
  const packet = {
    nodes: [
      { children: [1] },
      { children: [], extraNodeData: { name: 'wm' } }
    ]
  }

  assert.deepEqual(literalChildren(packet, 'wm'), [])
})

test('returns children below a nested literal path', () => {
  const packet = {
    nodes: [
      { children: [1] },
      { children: [2], extraNodeData: { name: 'wm' } },
      { children: [4, 3], extraNodeData: { name: 'tp' } },
      { children: [], extraNodeData: { name: 'self' } },
      { children: [], extraNodeData: { name: '--any' } }
    ]
  }

  assert.deepEqual(literalChildrenAt(packet, ['wm', 'tp']), ['--any', 'self'])
  assert.deepEqual(literalChildrenAt(packet, ['wm', 'missing']), undefined)
})

test('rejects malformed command node references', () => {
  assert.throws(() => literalChildren({ nodes: [{ children: [2] }] }, 'wm'), /invalid node 2/)
})