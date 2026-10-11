interface Decision {
  title: string
  body: string
  where: string[]
}

const DECISIONS: Decision[] = [
  {
    title: 'Payment waits for stock',
    body:
      'Payment listens for inventory-reserved, not order-created. Charging first and then finding the goods unavailable would mean issuing refunds instead of simply declining.',
    where: ['PaymentSagaListener.java'],
  },
  {
    title: 'Stock is reserved all or nothing',
    body:
      'Inventory checks every line before changing any of them. A partly reserved order would hold stock that can never be sold and never be released.',
    where: ['InventoryService.reserveForOrder'],
  },
  {
    title: 'Every consumer survives duplicates',
    body:
      'Kafka delivers at least once, so each service answers "what if I see this twice?" differently: order checks the status before changing it, inventory only releases or commits holds still marked RESERVED, and payment has a unique constraint on order_id so the database refuses a second charge.',
    where: ['OrderService.java', 'InventoryService.java', 'Payment.java'],
  },
  {
    title: 'Optimistic locking on stock',
    body:
      'Each stock row carries a @Version column. Two orders racing for the last unit conflict, and the loser fails and is retried instead of both succeeding and overselling.',
    where: ['InventoryItem.java'],
  },
  {
    title: 'Retry, then dead-letter',
    body:
      'A failing listener retries twice, a second apart, and then the message moves to <topic>.DLT so it stops blocking the partition. Malformed events skip the retries because they will never succeed.',
    where: ['KafkaMessagingConfig.java'],
  },
  {
    title: 'Save before publish',
    body:
      'Order-service commits the order and only then publishes order-created. If publishing failed, the order would wait in PENDING where a reconciliation job can find it. Publishing first could let other services act on an order that was never saved.',
    where: ['OrderService.placeOrder'],
  },
  {
    title: 'Choreography, not orchestration',
    body:
      'No coordinator decides the next step. Each service reacts to events and knows how to undo its own work. For three steps that keeps the services independent; past six or seven steps a central orchestrator becomes easier to follow.',
    where: ['OrderSagaListener.java', 'InventorySagaListener.java'],
  },
  {
    title: 'One topic per event type',
    body:
      'Each listener method handles exactly one event type with no manual type mapping, and an observer like saga-monitor can tell events apart from the topic name alone.',
    where: ['Topics.java'],
  },
]

export function Decisions() {
  return (
    <section id="decisions" className="section" aria-labelledby="decisions-title">
      <div className="section__intro">
        <h2 id="decisions-title" className="section__title">Decisions that keep it correct</h2>
        <p className="section__lede">
          The happy path is easy. These choices are what make the failure paths safe, and each one lives in a specific
          place in the code.
        </p>
      </div>

      <dl className="decisions">
        {DECISIONS.map((decision) => (
          <div key={decision.title} className="decision">
            <dt className="decision__title">{decision.title}</dt>
            <dd className="decision__body">{decision.body}</dd>
            <dd className="decision__where">
              {decision.where.map((file) => <code key={file}>{file}</code>)}
            </dd>
          </div>
        ))}
      </dl>
    </section>
  )
}
