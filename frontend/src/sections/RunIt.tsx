import { SITE } from '../site'

const PORTS: [string, string][] = [
  ['discovery-server (Eureka)', '18761'],
  ['api-gateway', '18080'],
  ['order-service', '18081'],
  ['inventory-service', '18082'],
  ['payment-service', '18083'],
  ['saga-monitor', '18084'],
  ['Kafka', '19092'],
  ['Kafka UI', '18090'],
  ['This page (dev server)', '15173'],
]

export function RunIt() {
  return (
    <section id="run-it" className="section" aria-labelledby="run-title">
      <div className="section__intro">
        <h2 id="run-title" className="section__title">Run the real services</h2>
        <p className="section__lede">
          The demo above runs in your browser. On a machine with JDK 17, Maven and Docker, the same page drives the
          real Spring Boot services.
          {SITE.demoVideoUrl && (
            <>
              {' '}Or <a href={SITE.demoVideoUrl} target="_blank" rel="noreferrer">watch a recording of them running</a>.
            </>
          )}
        </p>
      </div>

      <div className="run">
        <ol className="run__steps">
          <li>
            <p>Build every module once, from the repository root.</p>
            <pre><code>mvn clean install -DskipTests</code></pre>
          </li>
          <li>
            <p>Start Kafka and Kafka UI.</p>
            <pre><code>docker compose up -d</code></pre>
          </li>
          <li>
            <p>
              Start <code>discovery-server</code> first and give it about 20 seconds. Then start{' '}
              <code>order-service</code>, <code>inventory-service</code>, <code>payment-service</code>,{' '}
              <code>saga-monitor</code> and <code>api-gateway</code>, each in its own terminal.
            </p>
            <pre><code>cd order-service && mvn spring-boot:run</code></pre>
          </li>
          <li>
            <p>Start this page. It finds the gateway on port 18080 and switches to the real services.</p>
            <pre><code>{`cd frontend
npm install
npm run dev`}</code></pre>
          </li>
        </ol>

        <table className="ports">
          <caption>Ports, as configured in each service's application.yml</caption>
          <tbody>
            {PORTS.map(([name, port]) => (
              <tr key={name}>
                <th scope="row">{name}</th>
                <td><code>{port}</code></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
