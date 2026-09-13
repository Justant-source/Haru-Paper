import { Link } from 'react-router-dom'

export function MorePage() {
  return (
    <div className="page">
      <h1>더보기</h1>
      <ul className="more-list">
        <li>
          <Link to="/history">이력</Link>
        </li>
        <li>
          <Link to="/device">기기</Link>
        </li>
        <li>
          <Link to="/settings">설정</Link>
        </li>
      </ul>
    </div>
  )
}
