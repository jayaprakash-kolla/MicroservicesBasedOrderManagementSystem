export function WhySaga() {
  return (
    <section id="why" className="section" aria-labelledby="why-title">
      <div className="section__intro">
        <h2 id="why-title" className="section__title">Why there is nothing to roll back</h2>
        <p className="section__lede">
          Placing an order means three changes: save the order, hold the stock, charge the card. Where those changes
          live decides what happens when the last one fails.
        </p>
      </div>

      <div className="compare">
        <div className="compare__side">
          <h3 className="compare__title">In one database</h3>
          <pre className="compare__code" aria-label="A single transaction">
{`BEGIN
  INSERT INTO orders ...
  UPDATE stock ...
  charge card          declined
ROLLBACK               all three undone`}
          </pre>
          <p>The database undoes everything for you. This is the guarantee a monolith gives for free.</p>
        </div>

        <div className="compare__side">
          <h3 className="compare__title">Across three services</h3>
          <pre className="compare__code" aria-label="Three separate commits">
{`order_db       INSERT order     committed
inventory_db   UPDATE stock     committed
payment_db     charge card      declined
               ROLLBACK         reaches nothing`}
          </pre>
          <p>
            Each service commits to its own database, which no other service can reach. By the time the card is
            declined, the stock hold is already permanent in inventory's database.
          </p>
        </div>
      </div>

      <div className="prose">
        <p>
          A saga replaces the rollback with explicit undo steps. Each service publishes an event when it finishes its
          part, the next service reacts to that event, and a failure publishes an event that tells earlier services to
          reverse what they did. Here that reverse step is <code>order-cancelled</code>, which makes inventory release
          the stock it was holding.
        </p>
        <p>
          This project uses the choreography style: there is no coordinator. Each service only knows which events it
          listens to and how to undo its own work.
        </p>
      </div>
    </section>
  )
}
