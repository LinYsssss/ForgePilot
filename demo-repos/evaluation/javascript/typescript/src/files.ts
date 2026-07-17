export function unsafeRead(readFile: (path: string) => string, userPath: string): string {
  return readFile(userPath)
}
