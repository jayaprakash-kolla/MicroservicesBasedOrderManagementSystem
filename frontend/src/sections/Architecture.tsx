import type { ServiceName } from '../api/types'
import { SERVICES, SERVICE_PORT, TOPIC_INFO, type TopicName } from '../saga/topics'

const SERVICE_DETAIL: Record<ServiceName, { role: string; database: string; owns: string }> = {
  'order-service': {
    role: 'Starts the saga and owns the order status.',
    database: 'orderdb',
    owns: 'orders, order_line_item',
  },
  'inventory-service': {
    role: 'Holds stock, commits it on payment, releases it on cancellation.',
    database: 'inventorydb',
    owns: 'inventory_item, reservation',
  },
  'payment-service': {
    role: 'Charges the card through a gateway that declines above a limit.',
    database: 'paymentdb',
    owns: 'payment (unique per order)',
  },
}

const TOPIC_ORDER: TopicName[] = [
  'order-created',
  'inventory-reserved',
  'inventory-failed',
  'payment-completed',
  'payment-failed',
  'order-cancelled',
]

const SHORT: Record<ServiceName, string> = {
  'order-service': 'order',
  'inventory-service': 'inventory',
  'payment-service': 'payment',
}

export function Architecture() {
  return (
    <section id="architecture" className="section" aria-labelledby="architecture-title">
      <div className="section__intro">
        <h2 id="architecture-title" className="section__title">How it is put together</h2>
        <p className="section__lede">
          Requests come in through one gateway. Everything after that travels through Kafka, and no service ever reads
          another service's database.
        </p>
      </div>

      <div className="arch">
        <div className="arch__main">
          <div className="arch__node arch__node--edge">
            <span className="arch__name">Browser or curl</span>
            <span className="arch__detail">HTTP requests to <code>/api/v1/...</code></span>
          </div>

          <div className="arch__link" aria-hidden="true" />

          <div className="arch__node arch__node--edge">
            <span className="arch__name">api-gateway <code>:18080</code></span>
            <span className="arch__detail">
              Routes <code>/orders</code>, <code>/inventory</code> and <code>/payments</code> to{' '}
              <code>lb://SERVICE-NAME</code>, resolved through Eureka rather than fixed host names.
            </span>
          </div>

          <div className="arch__link" aria-hidden="true" />

          <ul className="arch__services">
            {SERVICES.map((service) => (
              <li key={service} className="arch__service" data-service={service}>
                <span className="arch__name">{service} <code>:{SERVICE_PORT[service]}</code></span>
                <span className="arch__detail">{SERVICE_DETAIL[service].role}</span>
                <span className="arch__db">
                  <code>{SERVICE_DETAIL[service].database}</code>
                  <span>{SERVICE_DETAIL[service].owns}</span>
                </span>
              </li>
            ))}
          </ul>

          <div className="arch__link" aria-hidden="true" />

          <div className="arch__kafka">
            <span className="arch__name">Apache Kafka <code>:19092</code></span>
            <span className="arch__detail">One topic per event type, every message keyed by order id.</span>
            <table className="topics">
              <caption className="visually-hidden">Who publishes and who reads each topic</caption>
              <thead>
                <tr>
                  <th scope="col">Topic</th>
                  <th scope="col">Published by</th>
                  <th scope="col">Read by</th>
                </tr>
              </thead>
              <tbody>
                {TOPIC_ORDER.map((topic) => (
                  <tr key={topic}>
                    <th scope="row"><code>{topic}</code></th>
                    <td><span className="service-tag" data-service={TOPIC_INFO[topic].publishedBy}>{SHORT[TOPIC_INFO[topic].publishedBy]}</span></td>
                    <td>
                      {TOPIC_INFO[topic].consumedBy.map((service) => (
                        <span key={service} className="service-tag" data-service={service}>{SHORT[service]}</span>
                      ))}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        <aside className="arch__side" aria-label="Supporting services">
          <div className="arch__node">
            <span className="arch__name">discovery-server <code>:18761</code></span>
            <span className="arch__detail">
              Netflix Eureka. Every service registers here on startup, and the gateway asks it where to send each
              request.
            </span>
          </div>
          <div className="arch__node">
            <span className="arch__name">saga-monitor <code>:18084</code></span>
            <span className="arch__detail">
              Reads every saga topic in its own consumer group and streams each message to this page over Server-Sent
              Events. The business services do not know it exists.
            </span>
          </div>
          <div className="arch__node">
            <span className="arch__name">Kafka UI <code>:18090</code></span>
            <span className="arch__detail">Browse the topics and consumer lag while the saga runs.</span>
          </div>
        </aside>
      </div>
    </section>
  )
}
