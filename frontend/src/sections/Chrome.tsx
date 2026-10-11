import { SITE } from '../site'

const NAV = [
  ['#demo', 'Demo'],
  ['#why', 'Why a saga'],
  ['#architecture', 'Architecture'],
  ['#decisions', 'Decisions'],
  ['#tests', 'Tests'],
  ['#run-it', 'Run it'],
] as const

export function TopBar() {
  return (
    <nav className="topbar" aria-label="Page">
      <a className="topbar__name" href="#top">Saga order system</a>
      <ul className="topbar__links">
        {NAV.map(([href, label]) => (
          <li key={href}><a href={href}>{label}</a></li>
        ))}
      </ul>
      <a className="topbar__repo" href={SITE.repositoryUrl} target="_blank" rel="noreferrer">GitHub</a>
    </nav>
  )
}

export function Footer() {
  return (
    <footer className="footer">
      <p className="footer__author">
        Built by {SITE.author}. {SITE.authorNote}.
      </p>
      <ul className="footer__links">
        <li><a href={SITE.repositoryUrl} target="_blank" rel="noreferrer">Source code</a></li>
        {SITE.githubProfileUrl && <li><a href={SITE.githubProfileUrl} target="_blank" rel="noreferrer">GitHub profile</a></li>}
        {SITE.linkedinUrl && <li><a href={SITE.linkedinUrl} target="_blank" rel="noreferrer">LinkedIn</a></li>}
      </ul>
    </footer>
  )
}
