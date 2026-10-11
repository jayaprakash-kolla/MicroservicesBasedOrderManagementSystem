const ITEMS = [
  {
    title: 'Transactional outbox',
    body: 'A crash between saving an order and publishing its event leaves it in PENDING. Writing the event to an outbox table in the same transaction, and relaying it from there, closes that gap.',
  },
  {
    title: 'Reconciliation job',
    body: 'Find orders stuck in PENDING or INVENTORY_RESERVED past a timeout and cancel them, which also triggers the stock release.',
  },
  {
    title: 'Reservation expiry',
    body: 'Stock held for a saga that never finished should release itself after a while instead of waiting for a cancellation that may never come.',
  },
  {
    title: 'Distributed tracing',
    body: 'Micrometer Tracing with Zipkin, so one order can be followed across four services without reading four logs.',
  },
  {
    title: 'Prices from a catalog',
    body: 'Order-service currently accepts the price the client sends. A catalog service would make the server the source of truth.',
  },
  {
    title: 'Circuit breakers at the gateway',
    body: 'Resilience4j on the synchronous hops, so a slow service fails fast instead of tying up gateway threads.',
  },
]

export function Roadmap() {
  return (
    <section id="next" className="section" aria-labelledby="next-title">
      <div className="section__intro">
        <h2 id="next-title" className="section__title">What a production build would add</h2>
        <p className="section__lede">This is a learning build. These are the gaps I know about and how I would close them.</p>
      </div>
      <ul className="roadmap">
        {ITEMS.map((item) => (
          <li key={item.title} className="roadmap__item">
            <h3 className="roadmap__title">{item.title}</h3>
            <p>{item.body}</p>
          </li>
        ))}
      </ul>
    </section>
  )
}
