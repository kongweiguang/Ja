// @author kongweiguang
import assert from "node:assert/strict";
import { chromium } from "@playwright/test";

/** 从实际截图读取轮廓采样点，避免只检查 CSS 字符串却漏掉渐变平铺造成的断色。 */
async function pixels(page, points) {
  const buffer = await page.screenshot();
  return page.evaluate(
    async ({ data, points }) => {
      const image = new globalThis.Image();
      image.src = data;
      await image.decode();
      const canvas = globalThis.document.createElement("canvas");
      canvas.width = image.width;
      canvas.height = image.height;
      const context = canvas.getContext("2d");
      context.drawImage(image, 0, 0);
      return points.map(([x, y]) => [...context.getImageData(x, y, 1, 1).data].slice(0, 3));
    },
    { data: `data:image/png;base64,${buffer.toString("base64")}`, points },
  );
}

const browser = await chromium.launch();
try {
  const page = await browser.newPage();
  await page.goto(
    process.argv[2] ??
      "http://localhost:1437/tests/features/navigation/ui/workspaceCornerBrowserFixture.html",
  );
  const handle = page.getByRole("separator");
  await handle.waitFor();
  for (const width of [1000, 760]) {
    await page.setViewportSize({ width, height: 700 });
    for (const palette of ["xcode", "fleet", "obsidian", "claude"]) {
      for (const mode of ["light", "dark"]) {
        await page.evaluate(
          ({ palette, mode }) => {
            globalThis.document.documentElement.dataset.palette = palette;
            globalThis.document.documentElement.dataset.theme = mode;
          },
          { palette, mode },
        );
        const bounds = await handle.boundingBox();
        const stage = await page.locator(".ja-workspace-stage").boundingBox();
        assert.equal(bounds.width, 11);
        const points = [
          [5, 5],
          [1, 20],
          [1, 36],
          [1, 52],
        ].map(([x, y]) => [Math.round(stage.x + x), Math.round(stage.y + y)]);
        await page.mouse.move(0, 0);
        await page.waitForTimeout(180);
        const before = await pixels(page, points);
        await page.mouse.move(bounds.x + 5, bounds.y + 36);
        await page.waitForTimeout(180);
        const after = await pixels(page, points);
        after.forEach((color, index) => {
          const difference = color.reduce(
            (sum, channel, channelIndex) => sum + Math.abs(channel - before[index][channelIndex]),
            0,
          );
          assert.ok(
            difference > 90,
            `${palette}/${mode}/${width}: broken contour at ${points[index]} (${difference})`,
          );
        });
      }
    }
  }
  const initial = Number(await handle.getAttribute("aria-valuenow"));
  const bounds = await handle.boundingBox();
  await page.mouse.move(bounds.x + 5, bounds.y + 36);
  await page.mouse.down();
  await page.mouse.move(bounds.x + 35, bounds.y + 36);
  await page.mouse.up();
  assert.ok(Number(await handle.getAttribute("aria-valuenow")) > initial);
  await handle.focus();
  const dragged = Number(await handle.getAttribute("aria-valuenow"));
  await page.keyboard.press("ArrowRight");
  assert.equal(Number(await handle.getAttribute("aria-valuenow")), dragged + 0.5);
  console.log(
    "PASS: 16 viewport/theme combinations, 64 contour pixel probes, pointer and keyboard resize",
  );
} finally {
  await browser.close();
}
