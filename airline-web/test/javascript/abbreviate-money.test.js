const fs = require("fs");
const path = require("path");
const vm = require("vm");

// Load just the helper from gadgets.js into a sandbox (no jQuery/DOM needed).
const src = fs.readFileSync(path.join(__dirname, "../../public/javascripts/gadgets.js"), "utf8");
const match = src.match(/function abbreviateMoney[\s\S]*?\n}/);
if (!match) throw new Error("abbreviateMoney not found in gadgets.js");
const sandbox = {};
vm.runInNewContext(match[0] + "\nthis.abbreviateMoney = abbreviateMoney;", sandbox);
const { abbreviateMoney } = sandbox;

describe("abbreviateMoney", () => {
  test("sub-thousand shows whole dollars", () => {
    expect(abbreviateMoney(0)).toBe("$0");
    expect(abbreviateMoney(950)).toBe("$950");
    expect(abbreviateMoney(999)).toBe("$999");
  });
  test("thousands", () => {
    expect(abbreviateMoney(1000)).toBe("$1K");
    expect(abbreviateMoney(1500)).toBe("$1.5K");
    expect(abbreviateMoney(340000)).toBe("$340K");
  });
  test("millions", () => {
    expect(abbreviateMoney(1200000)).toBe("$1.2M");
    expect(abbreviateMoney(324000000)).toBe("$324M");
  });
  test("billions", () => {
    expect(abbreviateMoney(2500000000)).toBe("$2.5B");
  });
  test("negative keeps sign", () => {
    expect(abbreviateMoney(-1200000)).toBe("-$1.2M");
  });
  test("non-finite returns dash", () => {
    expect(abbreviateMoney(undefined)).toBe("-");
    expect(abbreviateMoney(NaN)).toBe("-");
  });
});
