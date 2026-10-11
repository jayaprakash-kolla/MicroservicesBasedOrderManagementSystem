import type { CSSProperties, ReactNode } from 'react'
import type { SagaEvent, ServiceName } from '../api/types'
import { formatElapsed } from '../format'
import { describeEvent } from '../saga/describe'
import { SERVICES, SERVICE_PORT } from '../saga/topics'

/*
 * The signature view: a sequence diagram with one lane per service. Each row
 * is one Kafka message: a dot where it was published and an arrowhead at
 * every service that consumes it. Order-service is involved in every event,
 * so every arrow starts or ends in the left lane and labels can always sit
 * at that end.
 */

const LANE_LABEL: Record<ServiceName, string> = {
  'order-service': 'order',
  'inventory-service': 'inventory',
  'payment-service': 'payment',
}

function laneCenter(service: ServiceName): number {
  return ((SERVICES.indexOf(service) + 0.5) / SERVICES.length) * 100
}

interface SwimlaneProps {
  events: SagaEvent[]
  /** Smaller type and no summaries' second line, for the hero. */
  compact?: boolean
  /** Shown when there are no events yet. */
  emptyState?: ReactNode
  caption?: ReactNode
}

export function Swimlane({ events, compact = false, emptyState, caption }: SwimlaneProps) {
  const first = events[0]

  return (
    <figure className="swimlane" data-compact={compact || undefined}>
      <div className="swimlane__head" aria-hidden="true">
        <span className="swimlane__gutter" />
        {SERVICES.map((service) => (
          <div key={service} className="lane-head" data-service={service}>
            <span className="lane-head__name">{LANE_LABEL[service]}</span>
            <span className="lane-head__port">:{SERVICE_PORT[service]}</span>
          </div>
        ))}
      </div>

      <div className="swimlane__body">
        <div className="swimlane__lifelines" aria-hidden="true">
          <span className="swimlane__gutter" />
          {SERVICES.map((service) => (
            <span key={service} className="lifeline" data-service={service} />
          ))}
        </div>

        {events.length === 0 ? (
          <div className="swimlane__empty">{emptyState}</div>
        ) : (
          <ol className="swimlane__rows">
            {events.map((event, index) => (
              <SwimlaneRow
                key={`${event.topic}:${event.partition}:${event.offset}`}
                event={event}
                position={index + 1}
                elapsed={first && index > 0 ? formatElapsed(first.occurredAt, event.occurredAt) : 'start'}
                compact={compact}
              />
            ))}
          </ol>
        )}
      </div>

      {caption && <figcaption className="swimlane__caption">{caption}</figcaption>}
    </figure>
  )
}

interface SwimlaneRowProps {
  event: SagaEvent
  position: number
  elapsed: string
  compact: boolean
}

function SwimlaneRow({ event, position, elapsed, compact }: SwimlaneRowProps) {
  const description = describeEvent(event)
  if (!description) return null

  const origin = laneCenter(description.publishedBy)
  const targets = description.consumedBy.map((service) => ({ service, x: laneCenter(service) }))
  const xs = [origin, ...targets.map((target) => target.x)]
  const min = Math.min(...xs)
  const max = Math.max(...xs)
  const span = max - min

  const lineStyle: CSSProperties = {
    left: `${min}%`,
    width: `${span}%`,
    transformOrigin: span === 0 ? '50% 50%' : `${((origin - min) / span) * 100}% 50%`,
  }

  const consumers = description.consumedBy.map((service) => LANE_LABEL[service]).join(' and ')

  return (
    <li
      className="row"
      data-kind={description.kind}
      data-publisher={description.publishedBy}
      aria-label={`${position}. ${LANE_LABEL[description.publishedBy]} publishes ${event.topic}, read by ${consumers}. ${description.summary}`}
    >
      <span className="row__gutter" aria-hidden="true">
        <span className="row__seq">{position}</span>
        <span className="row__time">{elapsed}</span>
      </span>

      <div className="row__main" aria-hidden="true">
        <div className="row__track">
          <code className="row__topic" style={{ left: `${min}%` }}>
            {event.topic}
          </code>
          <span className="row__line" style={lineStyle} />
          <span className="row__origin" style={{ left: `${origin}%` }} />
          {targets.map((target) => (
            <span
              key={target.service}
              className="row__head"
              data-direction={target.x < origin ? 'left' : 'right'}
              style={{ left: `${target.x}%` }}
            />
          ))}
        </div>
        {!compact && <p className="row__summary">{description.summary}</p>}
      </div>
    </li>
  )
}
