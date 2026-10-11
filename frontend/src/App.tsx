import { Architecture } from './sections/Architecture'
import { Footer, TopBar } from './sections/Chrome'
import { Console } from './sections/Console'
import { Decisions } from './sections/Decisions'
import { Hero } from './sections/Hero'
import { Roadmap } from './sections/Roadmap'
import { RunIt } from './sections/RunIt'
import { Testing } from './sections/Testing'
import { WhySaga } from './sections/WhySaga'

export default function App() {
  return (
    <div className="page" id="top">
      <TopBar />
      <main>
        <Hero />
        <Console />
        <WhySaga />
        <Architecture />
        <Decisions />
        <Testing />
        <Roadmap />
        <RunIt />
      </main>
      <Footer />
    </div>
  )
}
