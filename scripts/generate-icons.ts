import path from "node:path";
import sharp from "sharp";

const source = path.resolve("public/icons/icon.svg");
await Promise.all([
  sharp(source).resize(192, 192).png().toFile(path.resolve("public/icons/icon-192.png")),
  sharp(source).resize(512, 512).png().toFile(path.resolve("public/icons/icon-512.png")),
  sharp(source).resize(512, 512).extend({ top: 48, bottom: 48, left: 48, right: 48, background: "#101317" }).resize(512, 512).png().toFile(path.resolve("public/icons/icon-maskable-512.png"))
]);
console.log("Generated Android and maskable PWA icons.");
