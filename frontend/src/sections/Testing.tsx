interface TestRow {
  name: string
  module: string
  kind: string
  count: number
  proves: string
}

const TESTS: TestRow[] = [
  { name: 'OrderServiceTest', module: 'order', kind: 'unit', count: 10, proves: 'Totals, legal status transitions, duplicate events ignored, and that only a payment failure publishes compensation.' },
  { name: 'OrderControllerTest', module: 'order', kind: 'web slice', count: 6, proves: '202 on accept, a message per invalid field, and the 404 body.' },
  { name: 'OrderSagaIntegrationTest', module: 'order', kind: 'embedded Kafka', count: 5, proves: 'A real round trip to CONFIRMED, both failure paths, and redelivered events.' },
  { name: 'InventoryServiceTest', module: 'inventory', kind: 'unit', count: 9, proves: 'All-or-nothing reservation, unknown SKUs, release and commit, each safe to repeat.' },
  { name: 'InventorySagaIntegrationTest', module: 'inventory', kind: 'embedded Kafka', count: 5, proves: 'Stock moves on order-created, returns on order-cancelled, and is committed on payment-completed.' },
  { name: 'SimulatedPaymentGatewayTest', module: 'payment', kind: 'unit', count: 3, proves: 'The decline threshold, including the exact boundary.' },
  { name: 'PaymentServiceTest', module: 'payment', kind: 'unit', count: 4, proves: 'Every path publishes exactly one event; a duplicate never reaches the gateway.' },
  { name: 'PaymentSagaIntegrationTest', module: 'payment', kind: 'embedded Kafka', count: 3, proves: 'Accept and decline branches, one payment row per order.' },
  { name: 'SagaEvent*Test', module: 'saga-monitor', kind: 'unit', count: 12, proves: 'Topic recognition, dead-letter copies, bounded buffer, and dropping disconnected browsers.' },
  { name: 'SagaMonitorIntegrationTest', module: 'saga-monitor', kind: 'embedded Kafka', count: 4, proves: 'Events published with the services’ own Kafka config are observed and served over HTTP and SSE.' },
  { name: 'simulation.test.ts', module: 'frontend', kind: 'Vitest', count: 11, proves: 'The in-browser model reaches the same outcomes, failure reasons and stock levels as the Java services.' },
]

export function Testing() {
  const total = TESTS.reduce((sum, row) => sum + row.count, 0)
  return (
    <section id="tests" className="section" aria-labelledby="tests-title">
      <div className="section__intro">
        <h2 id="tests-title" className="section__title">Tested without Docker</h2>
        <p className="section__lede">
          {total} tests. The integration tests start an embedded Kafka broker inside the test JVM, so{' '}
          <code>mvn test</code> needs no Docker, no Kafka and no Eureka. Each service's integration test plays the part
          of the other services by publishing the events they would have sent.
        </p>
      </div>

      <div className="table-scroll">
        <table className="tests">
          <thead>
            <tr>
              <th scope="col">Test</th>
              <th scope="col">Module</th>
              <th scope="col">Kind</th>
              <th scope="col" className="tests__count">Tests</th>
              <th scope="col">What it proves</th>
            </tr>
          </thead>
          <tbody>
            {TESTS.map((row) => (
              <tr key={row.name}>
                <th scope="row"><code>{row.name}</code></th>
                <td>{row.module}</td>
                <td>{row.kind}</td>
                <td className="tests__count">{row.count}</td>
                <td>{row.proves}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="hint">
        Asynchronous assertions use Awaitility instead of <code>Thread.sleep</code>, so they finish as soon as the
        pipeline settles and stay stable on a slow machine.
      </p>
    </section>
  )
}
