// Generates the app icon for both platforms without any image tooling:
// renders shapes with signed-distance functions, encodes PNGs by hand
// (zlib is built into Node) and assembles a Windows ICO.
//
// Icon: rounded-square indigo→blue gradient, white monitor + phone glyph.
import zlib from "zlib";
import fs from "fs";
import path from "path";

const ROOT = "/home/theo_unix/projects/remote-desktop";

// ---------- PNG encoder ----------

const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (const b of buf) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

/** rgba: Uint8Array of size*size*4 */
function encodePng(size, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8;  // bit depth
  ihdr[9] = 6;  // color type RGBA
  const raw = Buffer.alloc(size * (size * 4 + 1));
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0; // filter: none
    rgba.subarray(y * size * 4, (y + 1) * size * 4)
      .forEach((v, i) => (raw[y * (size * 4 + 1) + 1 + i] = v));
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    chunk("IDAT", zlib.deflateSync(raw, { level: 9 })),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

// ---------- shape rendering ----------

// Signed distance to a rounded rectangle (center cx/cy, half-size hw/hh, radius r).
function sdRoundRect(px, py, cx, cy, hw, hh, r) {
  const qx = Math.abs(px - cx) - (hw - r);
  const qy = Math.abs(py - cy) - (hh - r);
  const ax = Math.max(qx, 0);
  const ay = Math.max(qy, 0);
  return Math.hypot(ax, ay) + Math.min(Math.max(qx, qy), 0) - r;
}

const C0 = [99, 102, 241];  // indigo
const C1 = [29, 78, 216];   // blue

function gradient(x, y, S) {
  const t = Math.min(1, Math.max(0, (x + y) / (2 * S)));
  return [
    C0[0] + (C1[0] - C0[0]) * t,
    C0[1] + (C1[1] - C0[1]) * t,
    C0[2] + (C1[2] - C0[2]) * t,
  ];
}

// All shapes are designed on a 512x512 canvas.
// mode "full": rounded gradient tile + glyph (Windows ICO, legacy launcher)
// mode "fg":   glyph only, scaled into the adaptive-icon safe zone, transparent bg
function shapes(mode) {
  const glyph = [
    // monitor screen
    { sd: (x, y) => sdRoundRect(x, y, 226, 210, 130, 88, 14), col: "white" },
    // monitor stand neck + base
    { sd: (x, y) => sdRoundRect(x, y, 226, 312, 20, 16, 6), col: "white" },
    { sd: (x, y) => sdRoundRect(x, y, 226, 340, 60, 10, 8), col: "white" },
    // gap between monitor and phone (erase back to background)
    { sd: (x, y) => sdRoundRect(x, y, 368, 300, 78, 124, 34), col: "bg" },
    // phone body + inset screen
    { sd: (x, y) => sdRoundRect(x, y, 368, 300, 62, 108, 26), col: "white" },
    { sd: (x, y) => sdRoundRect(x, y, 368, 298, 48, 88, 16), col: "bg" },
  ];
  if (mode === "full") {
    return [{ sd: (x, y) => sdRoundRect(x, y, 256, 256, 256, 256, 92), col: "tile" }, ...glyph];
  }
  return glyph;
}

function render(size, mode) {
  const S = 512;
  const list = shapes(mode);
  const ss = 2; // supersampling per axis
  const out = new Uint8Array(size * size * 4);
  // fg glyph is scaled into the adaptive safe zone around the canvas center
  const fgScale = 0.58;

  for (let py = 0; py < size; py++) {
    for (let px = 0; px < size; px++) {
      let r = 0, g = 0, b = 0, a = 0;
      for (let sy = 0; sy < ss; sy++) {
        for (let sx = 0; sx < ss; sx++) {
          // pixel -> design coords
          let x = ((px + (sx + 0.5) / ss) / size) * S;
          let y = ((py + (sy + 0.5) / ss) / size) * S;
          if (mode === "fg") {
            x = (x - S / 2) / fgScale + S / 2;
            y = (y - S / 2) / fgScale + S / 2;
          }
          const aa = (S / size) * (mode === "fg" ? 1 / fgScale : 1); // design px per output px
          let cr = 0, cg = 0, cb = 0, ca = 0;
          for (const sh of list) {
            const d = sh.sd(x, y);
            const cov = Math.min(1, Math.max(0, 0.5 - d / aa));
            if (cov <= 0) continue;
            let sc;
            if (sh.col === "white") sc = [255, 255, 255];
            else sc = gradient(x, y, S); // "tile" and "bg" both sample the gradient
            // src-over
            cr = sc[0] * cov + cr * (1 - cov);
            cg = sc[1] * cov + cg * (1 - cov);
            cb = sc[2] * cov + cb * (1 - cov);
            ca = cov + ca * (1 - cov);
          }
          r += cr; g += cg; b += cb; a += ca;
        }
      }
      const n = ss * ss;
      const i = (py * size + px) * 4;
      out[i] = Math.round(r / n);
      out[i + 1] = Math.round(g / n);
      out[i + 2] = Math.round(b / n);
      out[i + 3] = Math.round((a / n) * 255);
    }
  }
  return out;
}

function png(size, mode) {
  return encodePng(size, render(size, mode));
}

// ---------- ICO ----------

function buildIco(sizes) {
  const pngs = sizes.map((s) => png(s, "full"));
  const header = Buffer.alloc(6);
  header.writeUInt16LE(0, 0);
  header.writeUInt16LE(1, 2); // icon
  header.writeUInt16LE(sizes.length, 4);
  const entries = [];
  let offset = 6 + 16 * sizes.length;
  sizes.forEach((s, i) => {
    const e = Buffer.alloc(16);
    e[0] = s >= 256 ? 0 : s;
    e[1] = s >= 256 ? 0 : s;
    e.writeUInt16LE(1, 4);  // planes
    e.writeUInt16LE(32, 6); // bpp
    e.writeUInt32LE(pngs[i].length, 8);
    e.writeUInt32LE(offset, 12);
    offset += pngs[i].length;
    entries.push(e);
  });
  return Buffer.concat([header, ...entries, ...pngs]);
}

// ---------- outputs ----------

function write(file, buf) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, buf);
  console.log(`${file}  (${buf.length} bytes)`);
}

// Windows
write(`${ROOT}/windows/RemoteDesktopWin/Assets/app.ico`, buildIco([16, 24, 32, 48, 64, 128, 256]));

// Android launcher (legacy) + adaptive foreground
const RES = `${ROOT}/android/app/src/main/res`;
const dpis = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };
for (const [dpi, mult] of Object.entries(dpis)) {
  write(`${RES}/mipmap-${dpi}/ic_launcher.png`, png(Math.round(48 * mult), "full"));
  write(`${RES}/mipmap-${dpi}/ic_launcher_foreground.png`, png(Math.round(108 * mult), "fg"));
}

// preview for a quick look


console.log("done");
