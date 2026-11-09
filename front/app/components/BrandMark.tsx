export default function BrandMark({ className = 'h-10 w-10' }: { className?: string }) {
  return (
    <svg viewBox="0 0 40 40" fill="none" className={className} aria-hidden="true">
      <rect width="40" height="40" rx="12" fill="currentColor" />
      <path d="M10 28V14l10 10 10-10v14M10 14l5-4m15 4-5-4" className="text-primary-foreground" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx="20" cy="10" r="2.2" className="text-primary-foreground" fill="currentColor" />
    </svg>
  )
}
