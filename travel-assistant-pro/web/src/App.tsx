import { useAuth } from './auth'
import { LoginPage } from './components/LoginPage'
import { Workspace } from './components/Workspace'

export default function App() {
  const { token } = useAuth()
  return token ? <Workspace/> : <LoginPage/>
}

