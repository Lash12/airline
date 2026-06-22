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
      }
    }
    return elements[selector]
  }

  // Mock document and localStorage
  const mockDocument = {
    hidden: false,
    addEventListener: jest.fn(),
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

    // Check demand rendering
    expect(elements['#forecastPaxDemand'].text).toHaveBeenCalledWith('750')
    expect(elements['#forecastCargoDemand'].text).toHaveBeenCalledWith('250')
    expect(elements['#forecastCargoDemandRow'].show).toHaveBeenCalled()

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
  })
})
