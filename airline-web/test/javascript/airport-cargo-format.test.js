'use strict'

const vm = require('vm')
const fs = require('fs')
const path = require('path')

const AIRPORT_CODE = fs.readFileSync(
  path.resolve(__dirname, '../../public/javascripts/airport.js'),
  'utf8'
)

test('formatCargoYieldPerUnitKm uses in-game cargo unit wording', () => {
  function $(selector) {
    return {
      on: jest.fn().mockReturnThis(),
      removeClass: jest.fn().mockReturnThis(),
      show: jest.fn().mockReturnThis(),
      hide: jest.fn().mockReturnThis(),
    }
  }

  const ctx = {
    $,
    jQuery: $,
    document: {},
    window: { matchMedia: jest.fn().mockReturnValue({ matches: false }) },
    Set,
    console,
    Math,
    Number,
    String,
  }
  vm.createContext(ctx)
  vm.runInContext(AIRPORT_CODE, ctx)

  expect(ctx.formatCargoYieldPerUnitKm(0.01)).toBe('$0.01 per cargo unit per km')
  expect(ctx.formatCargoYieldPerUnitKm(0.0125)).toBe('$0.0125 per cargo unit per km')
})
