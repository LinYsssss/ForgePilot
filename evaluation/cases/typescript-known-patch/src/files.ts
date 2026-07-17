import { readFileSync } from 'node:fs'
import { join } from 'node:path'
export function read(root: string, requested: string) {
  return readFileSync(join(root, requested), 'utf8')
}
