type IconProps = {
  className?: string
  active?: boolean
}

function Svg({ className, active, d }: IconProps & { d: string }) {
  return (
    <svg
      className={className}
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
    >
      <path
        d={d}
        stroke="currentColor"
        strokeWidth={active ? 2.5 : 1.5}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}

export function IconFormats(props: IconProps) {
  return (
    <Svg
      {...props}
      d="M7 3.5h10A1.5 1.5 0 0118.5 5v14A1.5 1.5 0 0117 20.5H7A1.5 1.5 0 015.5 19V5A1.5 1.5 0 017 3.5zM8.5 8h7M8.5 12h7M8.5 16h4"
    />
  )
}

export function IconSchedules(props: IconProps) {
  return (
    <Svg
      {...props}
      d="M7 4v2.5M17 4v2.5M4.5 8.5h15M6 6.5h12A1.5 1.5 0 0119.5 8v11A1.5 1.5 0 0118 20.5H6A1.5 1.5 0 014.5 19V8A1.5 1.5 0 016 6.5z"
    />
  )
}

export function IconPrint(props: IconProps) {
  return (
    <Svg
      {...props}
      d="M7 9V5.5h10V9M7 15.5H5.5A1.5 1.5 0 014 14V10.5A1.5 1.5 0 015.5 9h13A1.5 1.5 0 0120 10.5V14a1.5 1.5 0 01-1.5 1.5H17M7 13.5h10v6H7v-6z"
    />
  )
}

export function IconMore(props: IconProps) {
  return (
    <Svg
      {...props}
      d="M5 12h.01M12 12h.01M19 12h.01M6 12a1 1 0 11-2 0 1 1 0 012 0zm7 0a1 1 0 11-2 0 1 1 0 012 0zm7 0a1 1 0 11-2 0 1 1 0 012 0z"
    />
  )
}

export function IconBack(props: IconProps) {
  return <Svg {...props} d="M15 5l-7 7 7 7" />
}

export function IconKebab(props: IconProps) {
  return (
    <Svg
      {...props}
      d="M12 6.5a1 1 0 110-2 1 1 0 010 2zM12 13a1 1 0 110-2 1 1 0 010 2zM12 19.5a1 1 0 110-2 1 1 0 010 2z"
    />
  )
}

export function IconClose(props: IconProps) {
  return <Svg {...props} d="M6 6l12 12M18 6L6 18" />
}

export function IconChevron(props: IconProps) {
  return <Svg {...props} d="M9 6l6 6-6 6" />
}
