import { readFile, readdir } from "node:fs/promises";
import { extname, join, relative } from "node:path";

const root = new URL("../", import.meta.url);
const sourceRoot = new URL("../src/", import.meta.url);
const testRoot = new URL("../tests/", import.meta.url);
const rootFiles = ["vite.config.ts", "index.html"];
const allowedRawColorFile = "src/styles/tokens.css";
const forbiddenDependencies = [
  "axios",
  "ky",
  "pinia",
  "vuex",
  "@tanstack/vue-query",
  "tailwindcss",
  "element-plus",
  "vuetify",
  "naive-ui",
  "ant-design-vue",
  "primevue",
  "echarts",
  "chart.js",
  "d3",
];

async function filesUnder(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const path = join(directory.pathname, entry.name);
    if (entry.isDirectory()) {
      files.push(...(await filesUnder(new URL(`${entry.name}/`, directory))));
    } else if ([".css", ".ts", ".vue"].includes(extname(entry.name))) {
      files.push(path);
    }
  }
  return files;
}

const failures = [];
const scannedFiles = [
  ...(await filesUnder(sourceRoot)),
  ...(await filesUnder(testRoot)),
  ...rootFiles.map((name) => new URL(`../${name}`, import.meta.url).pathname),
];
for (const path of scannedFiles) {
  const content = await readFile(path, "utf8");
  const displayPath = relative(root.pathname, path);
  if (/\t|[ \t]+$/m.test(content)) {
    failures.push(`${displayPath}: tabs or trailing whitespace`);
  }
  if (displayPath !== allowedRawColorFile && /#[0-9a-f]{3,8}\b|\brgba?\(|\bhsla?\(/i.test(content)) {
    failures.push(`${displayPath}: raw color outside ${allowedRawColorFile}`);
  }
}

const packageJson = JSON.parse(await readFile(new URL("../package.json", import.meta.url), "utf8"));
const dependencies = { ...packageJson.dependencies, ...packageJson.devDependencies };
for (const name of forbiddenDependencies) {
  if (name in dependencies) {
    failures.push(`package.json: forbidden Phase 1 dependency ${name}`);
  }
}

if (failures.length > 0) {
  console.error(failures.join("\n"));
  process.exitCode = 1;
} else {
  console.log("Frontend foundation policy checks passed.");
}
