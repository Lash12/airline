'use strict'

const vm = require('vm')
const fs = require('fs')
const path = require('path')

const OFFICE_CODE = fs.readFileSync(
  path.resolve(__dirname, '../../public/javascripts/office.js'),
  'utf8'
)

function createTestContext() {
  const elements = {}

  function $(selector) {
    if (!elements[selector]) {
      elements[selector] = {
        append: jest.fn().mockReturnThis(),
        empty: jest.fn().mockReturnThis(),
        hide: jest.fn().mockReturnThis(),
        show: jest.fn().mockReturnThis(),
        off: jest.fn().mockReturnThis(),
        on: jest.fn().mockReturnThis(),
        ready: jest.fn().mockReturnThis(),
        text: jest.fn().mockReturnThis(),
        html: jest.fn().mockReturnThis(),
        val: jest.fn().mockReturnThis(),
        data: jest.fn().mockReturnValue(null),
      }
    }
    return elements[selector]
  }

  const ctx = {
    $,
    jQuery: $,
    document: {
      getElementById: jest.fn().mockReturnValue({
        addEventListener: jest.fn(),
        removeEventListener: jest.fn(),
        style: {},
        value: '',
      }),
    },
    activeAirline: { id: 1 },
    commaSeparateNumber: jest.fn((val) => String(val).replace(/\B(?=(\d{3})+(?!\d))/g, ',')),
    abbreviateMoney: jest.fn((val) => '$' + Math.round(Math.abs(val))),
    planLink: jest.fn(),
    planCargoLink: jest.fn(),
    showAirportDetails: jest.fn(),
    debounce: jest.fn((fn) => fn),
    console,
    Math,
    JSON,
    Number,
    String,
    parseInt,
    Array,
    setTimeout,
    clearTimeout,
  }

  vm.createContext(ctx)
  vm.runInContext(OFFICE_CODE, ctx)
  return { ctx, elements }
}

describe('renderAdvisorRecommendations', () => {
  test('renders grouped operational recommendations with safe actions', () => {
    const { ctx, elements } = createTestContext()

    ctx.renderAdvisorRecommendations({
      advisorTier: 4,
      recommendations: [
        {
          type: 'IDLE_AIRCRAFT',
          tier: 2,
          priority: 'HIGH',
          title: 'Idle aircraft available',
          summary: 'Use idle capacity on JFK to LAX.',
          details: 'Idle frames earn nothing.',
          estimatedImpact: '~$40,000/wk',
          risk: 'Confirm aircraft fit.',
          action: { label: 'Plan route', target: 'planRoute:3599-3600' },
        },
        {
          type: 'CARGO_OPPORTUNITY',
          tier: 2,
          priority: 'MEDIUM',
          title: 'Cargo lane to LHR',
          summary: '900 unserved cargo units.',
          details: 'Estimated yield $0.0100 per cargo unit per km.',
          estimatedImpact: 'High cargo potential',
          risk: 'Watch utilization.',
          action: { label: 'Plan cargo route', target: 'cargoRoute:3599-3601' },
        },
      ],
    })

    expect(elements['#advisorRecommendationsHeading'].show).toHaveBeenCalled()
    const html = elements['#advisorRecommendationsList'].append.mock.calls.map(c => c[0]).join('')
    expect(html).toContain('Fleet')
    expect(html).toContain('Cargo')
    expect(html).toContain('Idle aircraft available')
    expect(html).toContain('Cargo lane to LHR')
    expect(html).toContain('advisor-action-btn')
    expect(html).toContain('planRoute:3599-3600')
    expect(html).toContain('cargoRoute:3599-3601')
  })

  test('hides the advisor heading when no recommendations are returned', () => {
    const { ctx, elements } = createTestContext()

    ctx.renderAdvisorRecommendations({ advisorTier: 0, recommendations: [] })

    expect(elements['#advisorRecommendationsHeading'].hide).toHaveBeenCalled()
  })
})

describe('renderCargoMarketOverview', () => {
  test('renders cargo market lanes and hides plan button for served lanes', () => {
    const { ctx, elements } = createTestContext()

    ctx.renderCargoMarketOverview({
      lanes: [
        {
          originAirportId: 3599,
          originIata: 'JFK',
          destinationAirportId: 3600,
          destinationIata: 'LHR',
          cargoDemand: 1200,
          estimatedProfit: 450000,
          recommendedAircraft: ['Boeing 777F'],
          servedByPlayer: false,
          reason: 'Potential freighter lane.',
        },
        {
          originAirportId: 3599,
          originIata: 'JFK',
          destinationAirportId: 3601,
          destinationIata: 'ORD',
          cargoDemand: 500,
          estimatedProfit: 90000,
          recommendedAircraft: ['Boeing 737-800'],
          servedByPlayer: true,
          reason: 'You already serve this lane.',
        },
      ],
    })

    expect(elements['#cargoMarketOverviewStatus'].show).toHaveBeenCalled()
    const html = elements['#cargoMarketOverviewList'].append.mock.calls.map(c => c[0]).join('')
    expect(html).toContain('JFK → LHR')
    expect(html).toContain('Boeing 777F')
    expect(html).toContain('cargo-market-plan-btn')
    expect(html).toContain('JFK → ORD')
    expect(html).toContain('Served')
  })
})
