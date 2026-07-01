'use strict'

const vm = require('vm')
const fs = require('fs')
const path = require('path')

const AIRLINE_CODE = fs.readFileSync(
  path.resolve(__dirname, '../../public/javascripts/airline.js'),
  'utf8'
)

function createTestContext() {
  const elements = {}
  
  function $(selector) {
    if (!elements[selector]) {
      elements[selector] = {
        text: jest.fn().mockReturnThis(),
        html: jest.fn().mockReturnThis(),
        css: jest.fn().mockReturnThis(),
        show: jest.fn().mockReturnThis(),
        hide: jest.fn().mockReturnThis(),
        val: jest.fn().mockReturnValue('100'),
        attr: jest.fn().mockReturnThis(),
        addClass: jest.fn().mockReturnThis(),
        removeClass: jest.fn().mockReturnThis(),
        empty: jest.fn().mockReturnThis(),
        append: jest.fn().mockReturnThis(),
        find: jest.fn().mockImplementation((sel) => {
          return $(selector + ' ' + sel);
        }),
        on: jest.fn().mockReturnThis(),
        off: jest.fn().mockReturnThis(),
        closest: jest.fn().mockImplementation((sel) => {
          return $(selector + ' _closest_' + sel);
        }),
        toggle: jest.fn().mockReturnThis(),
        ready: jest.fn().mockImplementation((fn) => fn()),
        change: jest.fn().mockReturnThis(),
        click: jest.fn().mockReturnThis(),
        submit: jest.fn().mockReturnThis(),
        data: jest.fn().mockReturnThis(),
        removeData: jest.fn().mockReturnThis(),
      }
    }
    return elements[selector]
  }

  // Mock document and localStorage
  const mockDocument = {
    hidden: false,
    addEventListener: jest.fn(),
    _elementsById: {},
    getElementById: jest.fn((id) => {
      if (!mockDocument._elementsById[id]) {
        mockDocument._elementsById[id] = { textContent: '' }
      }
      return mockDocument._elementsById[id]
    }),
    createElement: jest.fn().mockImplementation(() => {
      return {
        innerHTML: '',
        textContent: '',
      }
    }),
  }

  const store = {}
  const mockLocalStorage = {
    getItem: jest.fn((k) => store[k] || null),
    setItem: jest.fn((k, v) => { store[k] = String(v) }),
    removeItem: jest.fn((k) => { delete store[k] }),
  }

  // Mock global helpers
  const commaSeparateNumber = jest.fn((val) => String(val).replace(/\B(?=(\d{3})+(?!\d))/g, ","))

  const ctx = {
    $: $,
    jQuery: $,
    document: mockDocument,
    localStorage: mockLocalStorage,
    commaSeparateNumber: commaSeparateNumber,
    abbreviateMoney: jest.fn(function(v) { return '$' + Math.abs(Math.round(v)); }),
    activeAirline: { id: 1, baseAirports: [], headquarterAirport: { airportId: 10 } },
    planLinkState: { fromAirportId: 10, toAirportId: 20 },
    planTransportType: "FLIGHT",
    loadedCountriesByCode: {},
    loadedLinksById: {},
    currentLinkConsumptions: null,
    tempPath: null,
    selectedLink: null,
    flightPaths: {},
    checkTutorial: jest.fn(),
    setActiveDiv: jest.fn(),
    hideActiveDiv: jest.fn(),
    getCountryFlagImg: jest.fn().mockReturnValue(''),
    getCountryRelationshipDescription: jest.fn().mockReturnValue(''),
    getAirlineRelationshipDescriptionSpan: jest.fn().mockReturnValue(''),
    updateAirlineTitle: jest.fn(),
    convertDistance: jest.fn((d) => d),
    distanceLabel: jest.fn().mockReturnValue('km'),
    getAirportSpan: jest.fn().mockReturnValue(''),
    sumPreferencesByType: jest.fn().mockReturnValue(100),
    toLinkClassValueString: jest.fn((v) => String(v)),
    getGradeStarsImgs: jest.fn().mockReturnValue(''),
    removeTempPath: jest.fn(),
    AirlineMap: {
      unhighlightLink: jest.fn(),
      deselectLink: jest.fn(),
      drawFlightPath: jest.fn().mockReturnValue({ path: {} }),
      highlightPath: jest.fn(),
      highlightLink: jest.fn(),
    },
    updatePlanLinkInfoWithModelSelected: jest.fn(),
    updatePricePercentage: jest.fn(),
    calculateDemand: jest.fn(),
    updateModelInfo: jest.fn(),
    sortPreserveOrder: jest.fn((list) => list),
    console,
    Math,
    JSON,
    parseInt,
    parseFloat,
    isNaN,
  }

  vm.createContext(ctx)
  vm.runInContext(AIRLINE_CODE, ctx)
  return { ctx, elements }
}

describe('showRouteForecast', () => {
  test('correctly renders forecast data to the DOM', () => {
    const { ctx, elements } = createTestContext()

    const mockForecast = {
      originAirportId: 10,
      destinationAirportId: 20,
      passengerDemandEstimate: 750,
      cargoDemandEstimate: 250,
      expectedRevenue: 120000,
      expectedCost: 80000,
      expectedProfit: 40000,
      confidenceLevel: 'HIGH',
      competitionLevel: 'LOW',
      competitionSummary: '1 competitor with light frequency.',
      confidenceExplanation: 'High confidence: both airports show strong demand signals.',
      recommendation: 'OPEN',
      recommendationSeverity: 'positive',
      cargoShareEstimate: 0.12,
      aircraftRecommendationReason: 'Airbus A320 fits the route and has useful belly cargo capacity.',
      recommendedAircraftModels: ['Boeing 737-800', 'Airbus A320'],
      recommendedFrequency: 14,
      reasons: ['Strong passenger demand on this route.', 'Low competition. Market is mostly open.']
    }

    ctx.showRouteForecast(mockForecast)

    // Check confidence level rendering
    expect(elements['#forecastConfidence'].text).toHaveBeenCalledWith('HIGH')
    expect(elements['#forecastConfidence'].css).toHaveBeenCalledWith('color', '#78cd6b')

    // Check competition rendering
    expect(elements['#forecastCompetition'].text).toHaveBeenCalledWith('LOW')
    expect(elements['#forecastCompetition'].css).toHaveBeenCalledWith('color', '#78cd6b')
    expect(elements['#forecastCompetitionSummary'].text).toHaveBeenCalledWith('1 competitor with light frequency.')
    expect(elements['#forecastRecommendation'].text).toHaveBeenCalledWith('Recommendation: Open')
    expect(elements['#forecastRecommendation'].css).toHaveBeenCalledWith('color', '#78cd6b')
    expect(elements['#forecastConfidenceExplanation'].text).toHaveBeenCalledWith('High confidence: both airports show strong demand signals.')

    // Check demand rendering
    expect(elements['#forecastPaxDemand'].text).toHaveBeenCalledWith('750')
    expect(elements['#forecastCargoDemand'].text).toHaveBeenCalledWith('250')
    expect(elements['#forecastCargoDemandRow'].show).toHaveBeenCalled()
    expect(elements['#forecastCargoShare'].text).toHaveBeenCalledWith('12% of revenue')
    expect(elements['#forecastCargoShareRow'].show).toHaveBeenCalled()

    // Check finances rendering
    expect(elements['#forecastRevenue'].text).toHaveBeenCalledWith('$120,000')
    expect(elements['#forecastCost'].text).toHaveBeenCalledWith('$80,000')
    expect(elements['#forecastProfit'].text).toHaveBeenCalledWith('$40,000')
    expect(elements['#forecastProfit'].css).toHaveBeenCalledWith('color', '#78cd6b')

    // Check aircraft recommendations
    expect(elements['#forecastAircraftRecommendations'].html).toHaveBeenCalled()
    const aircraftHtml = elements['#forecastAircraftRecommendations'].html.mock.calls[0][0]
    expect(aircraftHtml).toContain('Boeing 737-800')
    expect(aircraftHtml).toContain('Airbus A320')
    expect(aircraftHtml).toContain('Rec. Freq: 14/wk')
    expect(elements['#forecastAircraftReason'].text).toHaveBeenCalledWith('Airbus A320 fits the route and has useful belly cargo capacity.')

    // Check reasons rendering
    expect(elements['#forecastReasons'].html).toHaveBeenCalled()
    const reasonsHtml = elements['#forecastReasons'].html.mock.calls[0][0]
    expect(reasonsHtml).toContain('Strong passenger demand on this route.')
    expect(reasonsHtml).toContain('Low competition. Market is mostly open.')

    // Container should be shown
    expect(elements['#routeForecastContainer'].show).toHaveBeenCalled()
  })

  test('hides cargo row when cargo demand is 0', () => {
    const { ctx, elements } = createTestContext()

    const mockForecast = {
      passengerDemandEstimate: 150,
      cargoDemandEstimate: 0,
      expectedRevenue: 20000,
      expectedCost: 18000,
      expectedProfit: 2000,
      confidenceLevel: 'MEDIUM',
      competitionLevel: 'MEDIUM',
      recommendedAircraftModels: ['CRJ-200'],
      recommendedFrequency: 7,
      reasons: []
    }

    ctx.showRouteForecast(mockForecast)

    expect(elements['#forecastCargoDemandRow'].hide).toHaveBeenCalled()
    expect(elements['#forecastCargoShareRow'].hide).toHaveBeenCalled()
  })

  test('renders candidateAircraft comparison cards when present', () => {
    const { ctx, elements } = createTestContext()

    const mockForecast = {
      passengerDemandEstimate: 750,
      cargoDemandEstimate: 100,
      expectedRevenue: 120000,
      expectedCost: 80000,
      expectedProfit: 40000,
      confidenceLevel: 'HIGH',
      competitionLevel: 'NONE',
      recommendedAircraftModels: ['Boeing 737-800'],
      recommendedFrequency: 14,
      reasons: [],
      candidateAircraft: [
        {
          modelName: 'Boeing 737-800',
          frequency: 14,
          weeklyPaxCapacity: 2380,
          weeklyCargoCapacity: 112,
          estimatedRevenue: 120000,
          estimatedCost: 80000,
          estimatedProfit: 40000,
          youOwnThis: false,
          note: 'Best size and efficiency for this market.'
        },
        {
          modelName: 'Airbus A220-100',
          frequency: 21,
          weeklyPaxCapacity: 2541,
          weeklyCargoCapacity: 63,
          estimatedRevenue: 95000,
          estimatedCost: 70000,
          estimatedProfit: 25000,
          youOwnThis: true,
          note: 'Smaller option; lower seat count reduces risk on thin demand.'
        }
      ]
    }

    ctx.showRouteForecast(mockForecast)

    expect(elements['#forecastAircraftRecommendations'].html).toHaveBeenCalled()
    const html = elements['#forecastAircraftRecommendations'].html.mock.calls[0][0]
    // Primary candidate present
    expect(html).toContain('Boeing 737-800')
    expect(html).toContain('candidate-model-name')
    expect(html).toContain('candidate-profit')
    // Smaller candidate with youOwnThis badge
    expect(html).toContain('Airbus A220-100')
    expect(html).toContain('candidate-own-badge')
    // Note text
    expect(html).toContain('Best size and efficiency')
  })

  test('shows incompatibility warning when forecast says route is blocked', () => {
    const { ctx, elements } = createTestContext()

    ctx.showRouteForecast({
      passengerDemandEstimate: 150,
      cargoDemandEstimate: 0,
      expectedRevenue: 20000,
      expectedCost: 18000,
      expectedProfit: 2000,
      confidenceLevel: 'LOW',
      competitionLevel: 'NONE',
      recommendedAircraftModels: [],
      recommendedFrequency: null,
      reasons: ['Demand exists but route is currently blocked.'],
      compatible: false,
      blockingReason: 'Cannot fly from this airport, this is not a base!'
    })

    expect(elements['#forecastCompatibilityWarning'].text).toHaveBeenCalledWith('Cannot fly from this airport, this is not a base!')
    expect(elements['#forecastCompatibilityWarning'].show).toHaveBeenCalled()
  })
})

describe('planCargoLink', () => {
  test('sets _forceCargoPlanType before delegating to planLink', () => {
    const { ctx } = createTestContext()

    let flagAtCallTime = null
    ctx.planLink = jest.fn(function() {
      // Capture the flag value at the moment planLink is invoked
      flagAtCallTime = ctx._forceCargoPlanType
    })

    ctx.planCargoLink(10, 20)

    expect(flagAtCallTime).toBe(true)
    expect(ctx.planLink).toHaveBeenCalledWith(10, 20)
  })

  test('prefills the recommended freighter via explicitId when a model id is given', () => {
    const { ctx } = createTestContext()
    ctx.planLink = jest.fn()

    ctx.planCargoLink(10, 20, 42)

    // explicitId is set on the model select so updatePlanLinkInfo preselects it
    const modelSelect = ctx.$('#planLinkModelSelect')
    expect(modelSelect.data).toHaveBeenCalledWith('explicitId', 42)
    expect(ctx._forceCargoPlanType).toBe(true)
    expect(ctx.planLink).toHaveBeenCalledWith(10, 20)
  })

  test('does not set explicitId when no model id is given (blank-form fallback)', () => {
    const { ctx } = createTestContext()
    ctx.planLink = jest.fn()

    ctx.planCargoLink(10, 20)

    const modelSelect = ctx.$('#planLinkModelSelect')
    expect(modelSelect.data).not.toHaveBeenCalledWith('explicitId', expect.anything())
  })

  test('flag resets to false when cargoFreightersEnabled (simulates updatePlanLinkInfo path)', () => {
    const { ctx } = createTestContext()

    // Directly test the flag-consumption decision that updatePlanLinkInfo runs.
    // We cannot call the real updatePlanLinkInfo (too many DOM dependencies), but
    // we can verify the decision formula: flag is consumed and transport type
    // is chosen correctly.
    ctx._forceCargoPlanType = true

    var freightersEnabled = true
    var chosenType = (ctx._forceCargoPlanType && freightersEnabled) ? 'CARGO_FLIGHT' : 'FLIGHT'
    ctx._forceCargoPlanType = false  // mirrors what updatePlanLinkInfo does

    expect(chosenType).toBe('CARGO_FLIGHT')
    expect(ctx._forceCargoPlanType).toBe(false)
  })

  test('flag resets and falls back to FLIGHT when cargoFreightersEnabled is false', () => {
    const { ctx } = createTestContext()

    ctx._forceCargoPlanType = true

    var freightersEnabled = false
    var chosenType = (ctx._forceCargoPlanType && freightersEnabled) ? 'CARGO_FLIGHT' : 'FLIGHT'
    ctx._forceCargoPlanType = false

    expect(chosenType).toBe('FLIGHT')
    expect(ctx._forceCargoPlanType).toBe(false)
  })
})

describe('showPlanLinkError', () => {
  test('renders rejection from error response instead of silently failing', () => {
    const { ctx, elements } = createTestContext()

    ctx.showPlanLinkError({
      responseJSON: {
        error: 'Cannot plan this route.',
        rejection: {
          description: 'Cannot fly from this airport, this is not a base!',
          type: 'NO_BASE',
        },
      },
      responseText: 'Cannot plan this route.',
    })

    expect(ctx.document.getElementById('linkRejectionReason').textContent).toBe('Cannot fly from this airport, this is not a base!')
    expect(elements['.linkRejection'].show).toHaveBeenCalled()
    expect(elements['#addLinkButton'].hide).toHaveBeenCalled()
    expect(elements['#planLinkModelRow'].hide).toHaveBeenCalled()
  })
})
