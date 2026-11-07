// Records what thumbsup2's keyword search returns, for the app's Kotlin port
// of MiniSearch to be tested against: the same MiniSearch build thumbsup2
// ships (its node_modules), the same tokenizer, term processing and search
// options as thumbsup2's search page (src/website/search-assets/search.js).
//
//     node tools/make_search_reference.mjs
//
// Writes tools/reference/search.json: the documents, and for each query and
// set of fields, every result's id and score in MiniSearch's order.

import { readFileSync, writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const require = createRequire(join(here, '..', '..', 'thumbsup2', 'package.json'))
const MiniSearch = require('minisearch')

const FIELDS = ['aiCaption', 'aiOcr', 'caption', 'filename', 'path']
const tokenize = (text) => text.split(/[^\p{L}\p{N}]+/u).filter(Boolean)
const processTerm = (term) => term.toLowerCase()

// Deterministic pseudo-random numbers (mulberry32).
let seed = 20261107
function rand () {
  seed = (seed + 0x6D2B79F5) | 0
  let t = seed
  t = Math.imul(t ^ (t >>> 15), t | 1)
  t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296
}
const pick = (a) => a[Math.floor(rand() * a.length)]

const ref = JSON.parse(readFileSync(join(here, 'reference', 'thumbsup2_cuda.json'), 'utf8'))
const captions = Object.values(ref).map(r => r.caption)
const ocrs = Object.values(ref).map(r => r.ocr).filter(Boolean)
const words = [...new Set(captions.concat(ocrs).flatMap(tokenize))]
  .concat(['Straße', 'café', 'naïve', 'ΣΟΦΙΑ', 'Ödön', '東京', 'dog', 'dogs', 'doggo', 'cat', 'cats', 'catalog'])
const folders = ['DCIM/Camera/', 'Pictures/Screenshots/', 'Download/', 'Pictures/Messages/',
  'Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/', 'DCIM/Facebook/']

const docs = []
for (let i = 0; i < 400; i++) {
  const sentence = (n) => Array.from({ length: n }, () => pick(words)).join(' ')
  const name = (rand() < 0.5 ? 'IMG_2026' : 'Screenshot_2026') + String(1000 + i) + (rand() < 0.8 ? '.jpg' : '.png')
  docs.push({
    id: i,
    aiCaption: i < captions.length ? captions[i] : (rand() < 0.9 ? sentence(3 + Math.floor(rand() * 9)) : ''),
    aiOcr: rand() < 0.4 ? sentence(1 + Math.floor(rand() * 30)) : '',
    caption: rand() < 0.1 ? sentence(4) : '',
    filename: name,
    path: pick(folders) + name
  })
}

const ms = new MiniSearch({ fields: FIELDS, storeFields: [], tokenize, processTerm })
ms.addAll(docs)

const queries = [
  'cat', 'cats', 'ca', 'dog', 'dgo', 'couch', 'two cats', 'a', 'the bus', 'red bus street',
  'sleping', 'televison', 'kitchen white', 'stop sign', 'invoice', 'INVOICE 2047', 'grandma sunday',
  'screenshot', 'whatsapp images', 'IMG', 'jpg', 'png', 'camera', 'straße', 'strasse', 'cafe', 'café',
  'σοφια', '東京', 'exit 42', 'man playing tennis', 'zebra', 'zebras grass', 'xyzzy', 'e', '!!!',
  'people', 'group of people', 'motorcycle garage', 'bear', 'teddy'
]
const fieldSets = [FIELDS, ['aiCaption', 'aiOcr'], ['filename', 'path'], ['aiOcr']]

const results = []
for (const q of queries) {
  for (const fields of fieldSets) {
    const hits = ms.search(q, { fields, prefix: true, fuzzy: 0.2, combineWith: 'AND' })
    results.push({ query: q, fields, hits: hits.map(h => [h.id, h.score]) })
  }
}

writeFileSync(join(here, 'reference', 'search.json'),
  JSON.stringify({ fields: FIELDS, docs, results }, null, 0))
console.log(`${docs.length} documents, ${results.length} searches -> tools/reference/search.json`)
