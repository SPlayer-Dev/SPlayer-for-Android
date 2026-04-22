import { access, cp, mkdir, readFile, realpath, rm, writeFile } from "node:fs/promises";
import { builtinModules } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { build, transform } from "esbuild";

const currentFile = fileURLToPath(import.meta.url);
const rootDir = path.resolve(path.dirname(currentFile), "..");
const outDir = path.join(rootDir, "dist", "capacitor", "nodejs-project");
const vendorOutDir = path.join(outDir, "vendor", "netease-api");
const vendorNodeModulesOutDir = path.join(outDir, "vendor", "node_modules");
const neteaseApiRoot = await realpath(
  path.join(rootDir, "node_modules", "@neteasecloudmusicapienhanced", "api"),
);
const copiedRuntimePackages = new Set<string>();
const builtinModuleSet = new Set(
  builtinModules.flatMap((name) => [name, name.replace(/^node:/, "")]),
);

const transpileJavaScriptTree = async (rootPath: string) => {
  const stack = [rootPath];

  while (stack.length > 0) {
    const currentPath = stack.pop()!;
    const entries = await import("node:fs/promises").then(({ readdir }) =>
      readdir(currentPath, { withFileTypes: true }),
    );

    for (const entry of entries) {
      const entryPath = path.join(currentPath, entry.name);
      if (entry.isDirectory()) {
        stack.push(entryPath);
        continue;
      }

      if (!entry.isFile() || !entry.name.endsWith(".js") || entry.name === "README.js") continue;

      const source = await readFile(entryPath, "utf8");
      try {
        const result = await transform(source, {
          loader: "js",
          format: "cjs",
          target: "es2019",
          sourcemap: false,
          minify: false,
        });
        await writeFile(entryPath, result.code, "utf8");
      } catch (error) {
        console.warn(`Skip transpiling ${entryPath}:`, error);
      }
    }
  }
};

const resolveDependencyPackageJsonPath = async (
  packageName: string,
  searchFromPackageJsonPath: string,
) => {
  let currentDir = path.dirname(searchFromPackageJsonPath);

  while (true) {
    const candidatePackageJsonPath = path.join(
      currentDir,
      "node_modules",
      ...packageName.split("/"),
      "package.json",
    );

    try {
      await access(candidatePackageJsonPath);
      return await realpath(candidatePackageJsonPath);
    } catch {
      const parentDir = path.dirname(currentDir);
      if (parentDir === currentDir) {
        throw new Error(
          `Unable to resolve dependency ${packageName} from ${searchFromPackageJsonPath}`,
        );
      }
      currentDir = parentDir;
    }
  }
};

const copyRuntimePackage = async (packageName: string, searchFromPackageJsonPath: string) => {
  if (builtinModuleSet.has(packageName)) return;
  if (copiedRuntimePackages.has(packageName)) return;

  const packageJsonPath = await resolveDependencyPackageJsonPath(
    packageName,
    searchFromPackageJsonPath,
  );
  const packageRoot = path.dirname(packageJsonPath);
  const packageTargetRoot = path.join(vendorNodeModulesOutDir, ...packageName.split("/"));
  const packageJson = JSON.parse(await readFile(packageJsonPath, "utf8")) as {
    dependencies?: Record<string, string>;
    optionalDependencies?: Record<string, string>;
  };

  copiedRuntimePackages.add(packageName);

  await mkdir(path.dirname(packageTargetRoot), { recursive: true });
  await rm(packageTargetRoot, { recursive: true, force: true });
  await cp(packageRoot, packageTargetRoot, { recursive: true });
  await rm(path.join(packageTargetRoot, "node_modules"), { recursive: true, force: true });
  await transpileJavaScriptTree(packageTargetRoot);

  const runtimeDeps = new Set([
    ...Object.keys(packageJson.dependencies ?? {}),
    ...Object.keys(packageJson.optionalDependencies ?? {}),
  ]);

  for (const dependencyName of runtimeDeps) {
    await copyRuntimePackage(dependencyName, packageJsonPath);
  }
};

await mkdir(outDir, { recursive: true });
await mkdir(path.dirname(vendorOutDir), { recursive: true });

await build({
  entryPoints: [path.join(rootDir, "API", "mobile-entry.ts")],
  outfile: path.join(outDir, "main.js"),
  bundle: true,
  format: "cjs",
  platform: "node",
  target: ["es2019"],
  sourcemap: false,
  minify: false,
  plugins: [
    {
      name: "strip-node-prefix",
      setup(buildContext) {
        buildContext.onResolve({ filter: /^node:/ }, (args) => ({
          path: args.path.slice(5),
          external: true,
        }));
      },
    },
  ],
  banner: {
    js: "process.chdir(__dirname);",
  },
});

const mainBundlePath = path.join(outDir, "main.js");
const mainBundleSource = await readFile(mainBundlePath, "utf8");
const normalizedBundleSource = mainBundleSource
  .replace(/require\(("|')node:/g, "require($1")
  .replace(/__require\(("|')node:/g, "__require($1");

if (normalizedBundleSource !== mainBundleSource) {
  await writeFile(mainBundlePath, normalizedBundleSource, "utf8");
}

await rm(vendorOutDir, { recursive: true, force: true });
await cp(neteaseApiRoot, vendorOutDir, { recursive: true });
await rm(path.join(vendorOutDir, "node_modules"), { recursive: true, force: true });
await transpileJavaScriptTree(vendorOutDir);
await rm(vendorNodeModulesOutDir, { recursive: true, force: true });
await mkdir(vendorNodeModulesOutDir, { recursive: true });

const neteaseApiPackageJsonPath = path.join(neteaseApiRoot, "package.json");
const neteaseApiPackageJson = JSON.parse(await readFile(neteaseApiPackageJsonPath, "utf8")) as {
  dependencies?: Record<string, string>;
  optionalDependencies?: Record<string, string>;
};
const neteaseApiRuntimeDeps = new Set([
  ...Object.keys(neteaseApiPackageJson.dependencies ?? {}),
  ...Object.keys(neteaseApiPackageJson.optionalDependencies ?? {}),
]);

for (const dependencyName of neteaseApiRuntimeDeps) {
  await copyRuntimePackage(dependencyName, neteaseApiPackageJsonPath);
}

await writeFile(
  path.join(outDir, "package.json"),
  JSON.stringify(
    {
      name: "splayer-embedded-api",
      private: true,
      main: "main.js",
    },
    null,
    2,
  ) + "\n",
  "utf8",
);
